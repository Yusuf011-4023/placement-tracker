package com.placementtracker.sync;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.placementtracker.application.ApplicationRepository;
import com.placementtracker.application.Stage;
import com.placementtracker.classify.Classification;
import com.placementtracker.classify.ClassificationException;
import com.placementtracker.classify.EmailClassifier;
import com.placementtracker.classify.Relevance;
import com.placementtracker.classify.SuggestedAction;
import com.placementtracker.config.TrackerProperties;
import com.placementtracker.mail.EmailRepository;
import com.placementtracker.mail.EmailStatus;
import com.placementtracker.mail.MailAccount;
import com.placementtracker.mail.MailAuthException;
import com.placementtracker.mail.MailMessage;
import com.placementtracker.mail.MailSource;
import com.placementtracker.mail.MailSourceException;
import com.placementtracker.mail.PreFilter;
import com.placementtracker.processing.EmailProcessor;
import com.placementtracker.review.ReviewItem;
import com.placementtracker.review.ReviewReason;
import com.placementtracker.review.ReviewRepository;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class SyncServiceTest {

	static final Instant BACKFILL = Instant.parse("2026-06-01T00:00:00Z");

	@TempDir
	static Path tmp;

	@DynamicPropertySource
	static void props(DynamicPropertyRegistry registry) {
		registry.add("tracker.data-dir", () -> tmp.toString());
	}

	@Autowired PreFilter preFilter;
	@Autowired EmailProcessor processor;
	@Autowired EmailRepository emails;
	@Autowired SyncStateRepository state;
	@Autowired ReviewRepository reviews;
	@Autowired FailureRecorder failures;
	@Autowired ApplicationRepository apps;
	@Autowired TrackerProperties props;
	@Autowired com.placementtracker.classify.AiHealth aiHealth;
	@Autowired JdbcTemplate jdbc;

	FakeMailSource gmail;
	FakeMailSource outlook;
	FakeClassifier classifier;

	@BeforeEach
	void clean() {
		for (String t : new String[] { "review_items", "field_locks", "actions", "stage_history", "emails",
				"applications", "sync_state" }) {
			jdbc.update("DELETE FROM " + t);
		}
		processor.newActionIds();
		gmail = new FakeMailSource(MailAccount.GMAIL);
		outlook = new FakeMailSource(MailAccount.OUTLOOK);
		classifier = new FakeClassifier();
	}

	SyncService service(MailSource... sources) {
		return new SyncService(List.of(sources), preFilter, classifier, processor, emails, state, failures, aiHealth,
				props);
	}

	@Test
	void firstRunFetchesFromBackfillDate() {
		service(gmail).syncAll();

		assertThat(gmail.sinceCalls).containsExactly(BACKFILL);
	}

	@Test
	void laterRunFetchesFromHighWaterMinusOneDay() {
		Instant t = Instant.parse("2026-09-10T12:00:00Z");
		gmail.add(msg(MailAccount.GMAIL, "a", "<a@x>", "Application received", t));
		SyncService svc = service(gmail);
		svc.syncAll();
		assertThat(state.get(MailAccount.GMAIL).highWater()).isEqualTo(t);

		SyncReport second = svc.syncAll();

		assertThat(gmail.sinceCalls.get(1)).isEqualTo(t.minus(1, ChronoUnit.DAYS));
		assertThat(classifier.calls).isEqualTo(1);
		assertThat(second.fetched()).isZero(); // the overlap re-lists it, but an already-stored id is not fetched again
		assertThat(count("emails")).isEqualTo(1);
	}

	@Test
	void filteredEmailsNeverReachClassifier() {
		gmail.add(msg(MailAccount.GMAIL, "n", "<n@x>", "Weekly sale", "Big discounts on shoes",
				Instant.parse("2026-09-01T10:00:00Z")));

		service(gmail).syncAll();

		assertThat(classifier.calls).isZero();
		assertThat(status(MailAccount.GMAIL, "n")).isEqualTo(EmailStatus.FILTERED);
	}

	@Test
	void sync_skipsDuplicateAcrossAccounts() {
		Instant t = Instant.parse("2026-09-01T10:00:00Z");
		gmail.add(msg(MailAccount.GMAIL, "g1", "<same@x>", "Application received", t));
		outlook.add(msg(MailAccount.OUTLOOK, "o1", "<same@x>", "Application received", t));

		service(gmail, outlook).syncAll();

		assertThat(status(MailAccount.GMAIL, "g1")).isEqualTo(EmailStatus.PROCESSED);
		assertThat(status(MailAccount.OUTLOOK, "o1")).isEqualTo(EmailStatus.DUPLICATE);
		assertThat(apps.findAll()).hasSize(1);
		assertThat(classifier.calls).isEqualTo(1);
	}

	@Test
	void authFailureKeepsHighWaterAndMarksReconnect() {
		Instant t1 = Instant.parse("2026-09-01T10:00:00Z");
		gmail.add(msg(MailAccount.GMAIL, "a", "<a@x>", "Application received", t1));
		SyncService svc = service(gmail);
		svc.syncAll();
		Instant hw = state.get(MailAccount.GMAIL).highWater();

		gmail.add(msg(MailAccount.GMAIL, "b", "<b@x>", "Interview invitation", Instant.parse("2026-09-02T10:00:00Z")));
		gmail.authFail = true;
		SyncReport failed = svc.syncAll();

		assertThat(failed.statuses().get(MailAccount.GMAIL)).isEqualTo(SyncStatus.NEEDS_RECONNECT);
		assertThat(state.get(MailAccount.GMAIL).status()).isEqualTo(SyncStatus.NEEDS_RECONNECT);
		assertThat(state.get(MailAccount.GMAIL).highWater()).isEqualTo(hw);

		gmail.authFail = false;
		svc.syncAll();

		assertThat(status(MailAccount.GMAIL, "b")).isEqualTo(EmailStatus.PROCESSED);
		assertThat(count("emails")).isEqualTo(2);
		assertThat(state.get(MailAccount.GMAIL).status()).isEqualTo(SyncStatus.OK);
	}

	@Test
	void aiFailureRetriedThenReviewed() {
		gmail.add(msg(MailAccount.GMAIL, "bad", "<bad@x>", "Application BAD", Instant.parse("2026-09-01T10:00:00Z")));
		gmail.add(msg(MailAccount.GMAIL, "ok", "<ok@x>", "Application received", Instant.parse("2026-09-01T11:00:00Z")));
		classifier.failWhenSubjectContains = "BAD";
		SyncService svc = service(gmail);

		svc.syncAll();
		assertThat(status(MailAccount.GMAIL, "bad")).isEqualTo(EmailStatus.FAILED);
		assertThat(attempts("bad")).isEqualTo(1);
		assertThat(status(MailAccount.GMAIL, "ok")).isEqualTo(EmailStatus.PROCESSED);
		svc.syncAll();
		assertThat(attempts("bad")).isEqualTo(2);
		SyncReport third = svc.syncAll();

		assertThat(attempts("bad")).isEqualTo(3);
		assertThat(status(MailAccount.GMAIL, "bad")).isEqualTo(EmailStatus.REVIEW);
		List<ReviewItem> open = reviews.findOpen();
		assertThat(open).hasSize(1);
		assertThat(open.get(0).reason()).isEqualTo(ReviewReason.AI_FAILED);
		assertThat(third.newReviewItems()).isEqualTo(1);
		assertThat(status(MailAccount.GMAIL, "ok")).isEqualTo(EmailStatus.PROCESSED);
	}

	@Test
	void oneSourceFailingDoesNotBlockOther() {
		gmail.add(msg(MailAccount.GMAIL, "g", "<g@x>", "Application received", Instant.parse("2026-09-01T10:00:00Z")));
		outlook.sourceFail = true;

		SyncReport r = service(outlook, gmail).syncAll();

		assertThat(r.statuses().get(MailAccount.OUTLOOK)).isEqualTo(SyncStatus.ERROR);
		assertThat(r.statuses().get(MailAccount.GMAIL)).isEqualTo(SyncStatus.OK);
		assertThat(status(MailAccount.GMAIL, "g")).isEqualTo(EmailStatus.PROCESSED);
	}

	@Test
	void reportCountsNewReviewItemsAndActions() {
		gmail.add(msg(MailAccount.GMAIL, "c", "<c@x>", "Application received", Instant.parse("2026-09-01T10:00:00Z")));
		gmail.add(msg(MailAccount.GMAIL, "u", "<u@x>", "Application UNSURE", Instant.parse("2026-09-01T11:00:00Z")));
		classifier.unsureWhenSubjectContains = "UNSURE";
		classifier.actionDescription = "Complete the test";

		SyncReport r = service(gmail).syncAll();

		assertThat(r.fetched()).isEqualTo(2);
		assertThat(r.classified()).isEqualTo(2);
		assertThat(r.created()).isEqualTo(1);
		assertThat(r.updated()).isZero();
		assertThat(r.newReviewItems()).isEqualTo(1);
		assertThat(r.newActionIds()).hasSize(1);
		assertThat(r.aiConfigured()).isTrue();
	}

	@Test
	void noApiKeyLeavesEmailsPendingWithoutAttempts() {
		gmail.add(msg(MailAccount.GMAIL, "a", "<a@x>", "Application received", Instant.parse("2026-09-01T10:00:00Z")));
		gmail.add(msg(MailAccount.GMAIL, "n", "<n@x>", "Weekly sale", "shoes", Instant.parse("2026-09-01T11:00:00Z")));
		classifier.configured = false;
		SyncService svc = service(gmail);

		SyncReport r = svc.syncAll();
		svc.syncAll();

		assertThat(r.aiConfigured()).isFalse();
		assertThat(classifier.calls).isZero();
		assertThat(status(MailAccount.GMAIL, "a")).isEqualTo(EmailStatus.PENDING);
		assertThat(attempts("a")).isZero();
		assertThat(status(MailAccount.GMAIL, "n")).isEqualTo(EmailStatus.FILTERED);
	}

	@Test
	void pendingEmailsProcessedOnceKeyConfigured() {
		gmail.add(msg(MailAccount.GMAIL, "a", "<a@x>", "Application received", Instant.parse("2026-09-01T10:00:00Z")));
		classifier.configured = false;
		SyncService svc = service(gmail);
		svc.syncAll();
		gmail.messages.clear(); // the retry must re-fetch by id, not rely on the listing

		classifier.configured = true;
		svc.syncAll();

		assertThat(status(MailAccount.GMAIL, "a")).isEqualTo(EmailStatus.PROCESSED);
		assertThat(classifier.calls).isEqualTo(1);
		assertThat(apps.findAll()).hasSize(1);
	}

	@Test
	void deletedMessageOnRetryIsFilteredWithoutAttempt() {
		gmail.add(msg(MailAccount.GMAIL, "a", "<a@x>", "Application received", Instant.parse("2026-09-01T10:00:00Z")));
		classifier.configured = false;
		SyncService svc = service(gmail);
		svc.syncAll();
		gmail.messages.clear(); // deleted at the source: gone from the listing and from fetch-by-id
		gmail.fetchOneMap.clear();
		classifier.configured = true;

		svc.syncAll();

		assertThat(status(MailAccount.GMAIL, "a")).isEqualTo(EmailStatus.FILTERED);
		assertThat(attempts("a")).isZero();
	}

	@Test
	void unexpectedRuntimeExceptionIsolatedToOneEmail() {
		gmail.add(msg(MailAccount.GMAIL, "boom", "<boom@x>", "Application BOOM", Instant.parse("2026-09-01T10:00:00Z")));
		gmail.add(msg(MailAccount.GMAIL, "ok", "<ok@x>", "Application received", Instant.parse("2026-09-01T11:00:00Z")));
		classifier.runtimeWhenSubjectContains = "BOOM";

		SyncReport r = service(gmail).syncAll();

		assertThat(status(MailAccount.GMAIL, "boom")).isEqualTo(EmailStatus.FAILED);
		assertThat(attempts("boom")).isEqualTo(1);
		assertThat(lastError("boom")).contains("kaboom");
		assertThat(status(MailAccount.GMAIL, "ok")).isEqualTo(EmailStatus.PROCESSED);
		assertThat(r.statuses().get(MailAccount.GMAIL)).isEqualTo(SyncStatus.OK);
		assertThat(state.get(MailAccount.GMAIL).highWater()).isEqualTo(Instant.parse("2026-09-01T11:00:00Z"));
	}

	@Test
	void retryableFailuresDoNotConsumeAttempts() {
		gmail.add(msg(MailAccount.GMAIL, "r", "<r@x>", "Application RETRY", Instant.parse("2026-09-01T10:00:00Z")));
		classifier.failWhenSubjectContains = "RETRY";
		classifier.failRetryable = true;
		SyncService svc = service(gmail);

		for (int i = 0; i < 5; i++) {
			svc.syncAll();
		}

		assertThat(attempts("r")).isZero();
		assertThat(status(MailAccount.GMAIL, "r")).isEqualTo(EmailStatus.FAILED);
		assertThat(lastError("r")).contains("api down");
		assertThat(reviews.findOpen()).isEmpty();
		classifier.failWhenSubjectContains = null;
		svc.syncAll();
		assertThat(status(MailAccount.GMAIL, "r")).isEqualTo(EmailStatus.PROCESSED);
	}

	@Test
	void circuitBreakerStopsRunAfterThreeConsecutiveFailures() {
		for (int i = 0; i < 5; i++) {
			gmail.add(msg(MailAccount.GMAIL, "m" + i, "<m" + i + "@x>", "Application " + i,
					Instant.parse("2026-09-01T1" + i + ":00:00Z")));
		}
		classifier.failAll = true;

		service(gmail).syncAll();

		assertThat(classifier.calls).isEqualTo(3);
		assertThat(status(MailAccount.GMAIL, "m0")).isEqualTo(EmailStatus.FAILED);
		assertThat(status(MailAccount.GMAIL, "m3")).isEqualTo(EmailStatus.PENDING);
		assertThat(status(MailAccount.GMAIL, "m4")).isEqualTo(EmailStatus.PENDING);
		assertThat(count("emails WHERE attempts > 0")).isZero();
		assertThat(reviews.findOpen()).isEmpty();
	}

	@Test
	void failedAtCapIsReviewedNotLost() {
		gmail.add(msg(MailAccount.GMAIL, "x", "<x@x>", "Application received", Instant.parse("2026-09-01T10:00:00Z")));
		SyncService svc = service(gmail);
		classifier.configured = false;
		svc.syncAll();
		jdbc.update("UPDATE emails SET status = 'FAILED', attempts = 3 WHERE message_id = 'x'");
		classifier.configured = true;

		SyncReport r = svc.syncAll();

		assertThat(status(MailAccount.GMAIL, "x")).isEqualTo(EmailStatus.REVIEW);
		assertThat(reviews.findOpen()).hasSize(1);
		assertThat(reviews.findOpen().get(0).reason()).isEqualTo(ReviewReason.AI_FAILED);
		assertThat(r.newReviewItems()).isEqualTo(1);
		assertThat(classifier.calls).isZero();
	}

	@Test
	void duplicateRevivedWhenSurvivingCopyDeleted() {
		Instant t = Instant.parse("2026-09-01T10:00:00Z");
		gmail.add(msg(MailAccount.GMAIL, "g1", "<same@x>", "Application received", t));
		outlook.add(msg(MailAccount.OUTLOOK, "o1", "<same@x>", "Application received", t));
		classifier.configured = false;
		SyncService svc = service(gmail, outlook);
		svc.syncAll();
		assertThat(status(MailAccount.OUTLOOK, "o1")).isEqualTo(EmailStatus.DUPLICATE);
		gmail.messages.clear();
		gmail.fetchOneMap.clear(); // deleted at the source
		classifier.configured = true;

		svc.syncAll();

		assertThat(status(MailAccount.GMAIL, "g1")).isEqualTo(EmailStatus.FILTERED);
		assertThat(status(MailAccount.OUTLOOK, "o1")).isEqualTo(EmailStatus.PROCESSED);
		assertThat(apps.findAll()).hasSize(1);
	}

	@Test
	void futureDatedMessageDoesNotAdvanceHighWaterPastNow() {
		gmail.add(msg(MailAccount.GMAIL, "f", "<f@x>", "Application received", Instant.now().plus(30, ChronoUnit.DAYS)));

		service(gmail).syncAll();

		assertThat(state.get(MailAccount.GMAIL).highWater()).isBeforeOrEqualTo(Instant.now());
		assertThat(status(MailAccount.GMAIL, "f")).isEqualTo(EmailStatus.PROCESSED);
	}

	@Test
	void backlogThenKeyAppliesInDateOrder() {
		gmail.add(msg(MailAccount.GMAIL, "ot", "<ot@x>", "Online test invitation",
				Instant.parse("2026-09-01T10:00:00Z")));
		classifier.byMessageId.put("ot", new Classification(Relevance.YES, "Acme", "Analyst", Stage.ONLINE_TEST,
				List.of(new SuggestedAction("Complete the online test", "2026-09-08")), 0.95, "t"));
		classifier.configured = false;
		SyncService svc = service(gmail);
		svc.syncAll();
		assertThat(status(MailAccount.GMAIL, "ot")).isEqualTo(EmailStatus.PENDING);

		gmail.add(msg(MailAccount.GMAIL, "iv", "<iv@x>", "Interview invitation",
				Instant.parse("2026-09-05T10:00:00Z")));
		classifier.byMessageId.put("iv", new Classification(Relevance.YES, "Acme", "Analyst", Stage.INTERVIEW,
				List.of(new SuggestedAction("Attend interview", "2026-09-20")), 0.95, "t"));
		classifier.configured = true;
		svc.syncAll();

		assertThat(apps.findAll()).singleElement().satisfies(a -> assertThat(a.stage()).isEqualTo(Stage.INTERVIEW));
		long id = apps.findAll().get(0).id();
		assertThat(jdbc.queryForObject(
				"SELECT status FROM actions WHERE application_id = ? AND description = 'Attend interview'",
				String.class, id)).isEqualTo("OPEN");
		assertThat(status(MailAccount.GMAIL, "ot")).isEqualTo(EmailStatus.PROCESSED);
		assertThat(status(MailAccount.GMAIL, "iv")).isEqualTo(EmailStatus.PROCESSED);
		assertThat(reviews.findOpen()).isEmpty();
		// applied in date order: created at the online test, then moved forward by the invite
		assertThat(jdbc.queryForList("SELECT to_stage FROM stage_history WHERE application_id = ? ORDER BY id",
				String.class, id)).containsExactly("ONLINE_TEST", "INTERVIEW");
	}

	@Test
	void syncLoopsBatchesUntilCaughtUp() {
		gmail.batchLimit = 2;
		Instant newest = null;
		for (int i = 1; i <= 5; i++) {
			newest = Instant.parse("2026-09-0" + i + "T10:00:00Z");
			gmail.add(msg(MailAccount.GMAIL, "b" + i, "<b" + i + "@x>", "Application received", newest));
		}

		SyncReport report = service(gmail).syncAll();

		assertThat(count("emails")).isEqualTo(5);
		assertThat(classifier.calls).isEqualTo(5);
		assertThat(report.fetched()).isGreaterThanOrEqualTo(5);
		assertThat(state.get(MailAccount.GMAIL).highWater()).isEqualTo(newest);
		assertThat(gmail.sinceCalls.size()).isBetween(3, 20);
	}

	@Test
	void batchLoopStopsWhenHighWaterDoesNotAdvance() {
		gmail.batchLimit = 2;
		Instant same = Instant.parse("2026-09-01T10:00:00Z");
		for (int i = 1; i <= 4; i++) { // more than a batch, all with one timestamp: the mark cannot move
			gmail.add(msg(MailAccount.GMAIL, "s" + i, "<s" + i + "@x>", "Application received", same));
		}

		service(gmail).syncAll();

		assertThat(gmail.sinceCalls.size()).isLessThanOrEqualTo(2);
	}

	@Test
	void isRunningIsTrueOnlyDuringSyncAll() {
		boolean[] during = new boolean[1];
		SyncService[] svc = new SyncService[1];
		gmail.add(msg(MailAccount.GMAIL, "r1", "<r1@x>", "Application received", Instant.parse("2026-09-01T10:00:00Z")));
		svc[0] = service(new FakeMailSource(MailAccount.GMAIL) {
			@Override
			public List<MailMessage> fetchSince(Instant since) {
				during[0] = svc[0].isRunning();
				return List.of();
			}
		});

		assertThat(svc[0].isRunning()).isFalse();
		svc[0].syncAll();

		assertThat(during[0]).isTrue();
		assertThat(svc[0].isRunning()).isFalse();
	}

	@Test
	void classifiesEachBatchBeforeFetchingNext() {
		List<String> events = new ArrayList<>();
		FakeMailSource src = new FakeMailSource(MailAccount.GMAIL) {
			@Override
			public List<MailMessage> fetchSince(Instant since, java.util.function.Predicate<String> alreadyStored)
					throws MailAuthException, MailSourceException {
				events.add("fetch");
				return super.fetchSince(since, alreadyStored);
			}
		};
		src.batchLimit = 2;
		for (int i = 1; i <= 3; i++) {
			src.add(msg(MailAccount.GMAIL, "e" + i, "<e" + i + "@x>", "Application received",
					Instant.parse("2026-09-0" + i + "T10:00:00Z")));
		}
		classifier = new FakeClassifier() {
			@Override
			public Classification classify(MailMessage m) throws ClassificationException {
				events.add("classify:" + m.messageId());
				return super.classify(m);
			}
		};

		service(src).syncAll();

		assertThat(events.subList(0, 4)).containsExactly("fetch", "classify:e1", "classify:e2", "fetch");
		assertThat(classifier.calls).isEqualTo(3);
	}

	@Test
	void retryableFailedEmailAttemptedOncePerRun() {
		gmail.add(msg(MailAccount.GMAIL, "f", "<f@x>", "Application FAIL", Instant.parse("2026-08-01T10:00:00Z")));
		classifier.configured = false;
		service(gmail).syncAll();
		jdbc.update("UPDATE emails SET status = 'FAILED', attempts = 0 WHERE message_id = 'f'");
		classifier.configured = true;
		classifier.failWhenSubjectContains = "FAIL";
		gmail.batchLimit = 2;
		for (int i = 1; i <= 6; i++) {
			gmail.add(msg(MailAccount.GMAIL, "n" + i, "<n" + i + "@x>", "Application received",
					Instant.parse("2026-09-0" + i + "T10:00:00Z")));
		}

		service(gmail).syncAll();

		assertThat(gmail.sinceCalls.size()).isGreaterThanOrEqualTo(3);
		assertThat(attempts("f")).isEqualTo(1);
	}

	@Test
	void breakerOpenStillStoresLaterBatches() {
		gmail.batchLimit = 2;
		for (int i = 1; i <= 6; i++) {
			gmail.add(msg(MailAccount.GMAIL, "k" + i, "<k" + i + "@x>", "Application received",
					Instant.parse("2026-09-0" + i + "T10:00:00Z")));
		}
		classifier.failAll = true;

		service(gmail).syncAll();

		assertThat(count("emails")).isEqualTo(6);
		assertThat(classifier.calls).isEqualTo(SyncService.BREAKER_THRESHOLD);
		assertThat(count("emails WHERE status = 'FAILED' AND attempts > 0")).isZero();
		assertThat(count("emails WHERE attempts > 0")).isZero();
	}

	@Test
	void progressVisibleOnlyWhileRunning() {
		gmail.add(msg(MailAccount.GMAIL, "p1", "<p1@x>", "Application received", Instant.parse("2026-09-01T10:00:00Z")));
		SyncService[] svc = new SyncService[1];
		List<Optional<SyncProgress>> seen = new ArrayList<>();
		classifier = new FakeClassifier() {
			@Override
			public Classification classify(MailMessage m) throws ClassificationException {
				seen.add(svc[0].progress());
				return super.classify(m);
			}
		};
		svc[0] = service(gmail);

		assertThat(svc[0].progress()).isEmpty();
		svc[0].syncAll();

		assertThat(seen).hasSize(1);
		assertThat(seen.get(0)).isPresent();
		assertThat(seen.get(0).get().downloaded()).isGreaterThanOrEqualTo(1);
		assertThat(svc[0].progress()).isEmpty();
	}

	// ---- helpers ----

	int count(String table) {
		return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
	}

	EmailStatus status(MailAccount a, String messageId) {
		return EmailStatus.valueOf(jdbc.queryForObject(
				"SELECT status FROM emails WHERE account = ? AND message_id = ?", String.class, a.name(), messageId));
	}

	int attempts(String messageId) {
		return jdbc.queryForObject("SELECT attempts FROM emails WHERE message_id = ?", Integer.class, messageId);
	}

	String lastError(String messageId) {
		return jdbc.queryForObject("SELECT last_error FROM emails WHERE message_id = ?", String.class, messageId);
	}

	static MailMessage msg(MailAccount a, String id, String imid, String subject, Instant at) {
		return msg(a, id, imid, subject, "Thanks for your application.", at);
	}

	static MailMessage msg(MailAccount a, String id, String imid, String subject, String body, Instant at) {
		return new MailMessage(a, id, "t-" + id, imid, "Recruiter <jobs@acme.com>", subject, at, body, "http://x/" + id,
				Set.of());
	}

	static class FakeMailSource implements MailSource {
		final MailAccount account;
		final List<MailMessage> messages = new ArrayList<>();
		final Map<String, MailMessage> fetchOneMap = new HashMap<>();
		final List<Instant> sinceCalls = new ArrayList<>();
		boolean authFail;
		boolean sourceFail;
		int batchLimit = Integer.MAX_VALUE;
		java.util.function.Predicate<String> skip = id -> false;

		FakeMailSource(MailAccount account) {
			this.account = account;
		}

		void add(MailMessage m) {
			messages.add(m);
			fetchOneMap.put(m.messageId(), m);
		}

		public MailAccount account() {
			return account;
		}

		public boolean isConnected() {
			return true;
		}

		public void connect() {
		}

		public List<MailMessage> fetchSince(Instant since) throws MailAuthException, MailSourceException {
			sinceCalls.add(since);
			if (authFail) {
				throw new MailAuthException("expired", null);
			}
			if (sourceFail) {
				throw new MailSourceException("down", null);
			}
			return messages.stream().filter(m -> !m.receivedAt().isBefore(since))
					.filter(m -> !skip.test(m.messageId())).sorted(Comparator.comparing(MailMessage::receivedAt))
					.limit(batchLimit).toList();
		}

		public List<MailMessage> fetchSince(Instant since, java.util.function.Predicate<String> alreadyStored)
				throws MailAuthException, MailSourceException {
			skip = alreadyStored; // like the Gmail source, stored ids are not returned and do not count toward the limit
			return fetchSince(since);
		}

		public int batchLimit() {
			return batchLimit;
		}

		public Optional<MailMessage> fetchOne(String messageId) {
			return Optional.ofNullable(fetchOneMap.get(messageId));
		}
	}

	static class FakeClassifier implements EmailClassifier {
		boolean configured = true;
		int calls;
		String failWhenSubjectContains;
		boolean failRetryable;
		boolean failAll;
		String runtimeWhenSubjectContains;
		String unsureWhenSubjectContains;
		String actionDescription;
		final Map<String, Classification> byMessageId = new HashMap<>();

		public boolean isConfigured() {
			return configured;
		}

		public Classification classify(MailMessage m) throws ClassificationException {
			calls++;
			if (byMessageId.containsKey(m.messageId())) {
				return byMessageId.get(m.messageId());
			}
			if (failAll) {
				throw new ClassificationException("outage", true);
			}
			if (failWhenSubjectContains != null && m.subject().contains(failWhenSubjectContains)) {
				throw new ClassificationException("api down", failRetryable);
			}
			if (runtimeWhenSubjectContains != null && m.subject().contains(runtimeWhenSubjectContains)) {
				throw new IllegalStateException("kaboom");
			}
			if (unsureWhenSubjectContains != null && m.subject().contains(unsureWhenSubjectContains)) {
				return new Classification(Relevance.UNSURE, "Other", "Analyst", Stage.APPLIED, List.of(), 0.9, "t");
			}
			List<SuggestedAction> actions = actionDescription == null ? List.of()
					: List.of(new SuggestedAction(actionDescription, null));
			// distinct company per message so the matcher creates a new application each time
			return new Classification(Relevance.YES, "Company " + m.messageId(), "Analyst", Stage.APPLIED, actions,
					0.95, "t");
		}
	}
}
