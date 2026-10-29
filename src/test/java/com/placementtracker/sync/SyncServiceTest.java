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

	static final Instant BACKFILL = Instant.parse("2026-08-01T00:00:00Z");

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
	@Autowired ApplicationRepository apps;
	@Autowired TrackerProperties props;
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
		return new SyncService(List.of(sources), preFilter, classifier, processor, emails, state, reviews, props);
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
		assertThat(second.fetched()).isEqualTo(1);
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
					.sorted(Comparator.comparing(MailMessage::receivedAt)).toList();
		}

		public Optional<MailMessage> fetchOne(String messageId) {
			return Optional.ofNullable(fetchOneMap.get(messageId));
		}
	}

	static class FakeClassifier implements EmailClassifier {
		boolean configured = true;
		int calls;
		String failWhenSubjectContains;
		String runtimeWhenSubjectContains;
		String unsureWhenSubjectContains;
		String actionDescription;

		public boolean isConfigured() {
			return configured;
		}

		public Classification classify(MailMessage m) throws ClassificationException {
			calls++;
			if (failWhenSubjectContains != null && m.subject().contains(failWhenSubjectContains)) {
				throw new ClassificationException("api down", true);
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
