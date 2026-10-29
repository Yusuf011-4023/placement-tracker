package com.placementtracker.processing;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.placementtracker.application.ActionRepository;
import com.placementtracker.application.Application;
import com.placementtracker.application.ApplicationAction;
import com.placementtracker.application.ApplicationRepository;
import com.placementtracker.application.HistoryRepository;
import com.placementtracker.application.Stage;
import com.placementtracker.application.StageChange;
import com.placementtracker.classify.Classification;
import com.placementtracker.classify.Relevance;
import com.placementtracker.classify.SuggestedAction;
import com.placementtracker.mail.EmailRecord;
import com.placementtracker.mail.EmailRepository;
import com.placementtracker.mail.EmailStatus;
import com.placementtracker.mail.MailAccount;
import com.placementtracker.mail.MailMessage;
import com.placementtracker.matching.CompanyNames;
import com.placementtracker.review.ReviewItem;
import com.placementtracker.review.ReviewReason;
import com.placementtracker.review.ReviewRepository;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class EmailProcessorTest {

	@TempDir
	static Path tmp;

	@DynamicPropertySource
	static void props(DynamicPropertyRegistry registry) {
		registry.add("tracker.data-dir", () -> tmp.toString());
	}

	@Autowired EmailProcessor processor;
	@Autowired ApplicationRepository apps;
	@Autowired ActionRepository actions;
	@Autowired HistoryRepository history;
	@Autowired EmailRepository emails;
	@Autowired ReviewRepository reviews;
	@Autowired JdbcTemplate jdbc;

	int seq;

	@BeforeEach
	void clean() {
		for (String t : new String[] { "review_items", "field_locks", "actions", "stage_history", "emails",
				"applications" }) {
			jdbc.update("DELETE FROM " + t);
		}
		processor.newActionIds();
	}

	@Test
	void noRelevanceMarksNotPlacement() {
		EmailRecord e = email("t1");

		ProcessOutcome o = processor.process(e, mail("t1"), cls(Relevance.NO, null, null, null, 0.95));

		assertThat(o).isEqualTo(ProcessOutcome.NOT_PLACEMENT);
		assertThat(emails.find(e.id()).orElseThrow().status()).isEqualTo(EmailStatus.NOT_PLACEMENT);
		assertThat(apps.findAll()).isEmpty();
		assertThat(reviews.findOpen()).isEmpty();
	}

	@Test
	void unsureGoesToReviewLowConfidence() {
		EmailRecord e = email("t1");

		ProcessOutcome o = processor.process(e, mail("t1"),
				cls(Relevance.UNSURE, "Acme", "Analyst", Stage.APPLIED, 0.9));

		assertThat(o).isEqualTo(ProcessOutcome.REVIEW);
		assertThat(emails.find(e.id()).orElseThrow().status()).isEqualTo(EmailStatus.REVIEW);
		assertThat(reviews.findOpen()).singleElement().satisfies(r -> {
			assertThat(r.reason()).isEqualTo(ReviewReason.LOW_CONFIDENCE);
			assertThat(r.emailId()).isEqualTo(e.id());
		});
		assertThat(apps.findAll()).isEmpty();
	}

	@Test
	void lowConfidenceYesGoesToReview() {
		EmailRecord e = email("t1");

		ProcessOutcome o = processor.process(e, mail("t1"),
				cls(Relevance.YES, "Acme", "Analyst", Stage.APPLIED, 0.6));

		assertThat(o).isEqualTo(ProcessOutcome.REVIEW);
		assertThat(reviews.findOpen()).singleElement().satisfies(r -> {
			assertThat(r.reason()).isEqualTo(ReviewReason.LOW_CONFIDENCE);
			assertThat(r.suggestionJson()).contains("Acme");
		});
		assertThat(apps.findAll()).isEmpty();
	}

	@Test
	void newApplicationCreatedWithHistoryAndActions() {
		EmailRecord e = email("t1");
		Classification c = new Classification(Relevance.YES, "Acme Ltd", "Technology Placement", Stage.ONLINE_TEST,
				List.of(new SuggestedAction("Complete the online test", "2026-11-07")), 0.9, "x");

		ProcessOutcome o = processor.process(e, mail("t1"), c);

		assertThat(o).isEqualTo(ProcessOutcome.CREATED);
		Application a = apps.findAll().get(0);
		assertThat(a.company()).isEqualTo("Acme Ltd");
		assertThat(a.companyKey()).isEqualTo(CompanyNames.key("Acme Ltd"));
		assertThat(a.stage()).isEqualTo(Stage.ONLINE_TEST);
		List<StageChange> h = history.forApplication(a.id());
		assertThat(h).singleElement().satisfies(s -> {
			assertThat(s.fromStage()).isNull();
			assertThat(s.toStage()).isEqualTo(Stage.ONLINE_TEST);
			assertThat(s.source()).isEqualTo("EMAIL");
			assertThat(s.emailId()).isEqualTo(e.id());
		});
		assertThat(actions.findForApplication(a.id())).singleElement().satisfies(x -> {
			assertThat(x.status()).isEqualTo(ApplicationAction.Status.OPEN);
			assertThat(x.description()).isEqualTo("Complete the online test");
			assertThat(x.stage()).isEqualTo(Stage.ONLINE_TEST);
		});
		EmailRecord after = emails.find(e.id()).orElseThrow();
		assertThat(after.applicationId()).isEqualTo(a.id());
		assertThat(after.status()).isEqualTo(EmailStatus.PROCESSED);
		assertThat(processor.newActionIds()).hasSize(1);
		assertThat(processor.newActionIds()).isEmpty();
	}

	@Test
	void notSubmittedCreatesEntryWithFinishAction() {
		EmailRecord e = email("t1");

		ProcessOutcome o = processor.process(e, mail("t1"),
				cls(Relevance.YES, "Acme", "Analyst", Stage.NOT_SUBMITTED, 0.9));

		assertThat(o).isEqualTo(ProcessOutcome.CREATED);
		Application a = apps.findAll().get(0);
		assertThat(a.stage()).isEqualTo(Stage.NOT_SUBMITTED);
		assertThat(actions.findForApplication(a.id())).singleElement()
				.satisfies(x -> assertThat(x.description()).isEqualTo("Finish your Acme application"));
	}

	@Test
	void forwardMoveClosesPreviousStageActions() {
		long id = apps.insert("Acme", "acme", "Analyst", Stage.ONLINE_TEST, null, null);
		long old = actions.insert(id, "Do test", null, Stage.ONLINE_TEST, null);
		linkThread(id, "t1");

		ProcessOutcome o = processor.process(email("t1"), mail("t1"),
				cls(Relevance.YES, "Acme", "Analyst", Stage.INTERVIEW, 0.9));

		assertThat(o).isEqualTo(ProcessOutcome.UPDATED);
		assertThat(apps.find(id).orElseThrow().stage()).isEqualTo(Stage.INTERVIEW);
		assertThat(actions.findForApplication(id)).singleElement().satisfies(x -> {
			assertThat(x.id()).isEqualTo(old);
			assertThat(x.status()).isEqualTo(ApplicationAction.Status.CLOSED);
		});
		assertThat(history.forApplication(id)).singleElement().satisfies(s -> {
			assertThat(s.fromStage()).isEqualTo(Stage.ONLINE_TEST);
			assertThat(s.toStage()).isEqualTo(Stage.INTERVIEW);
		});
		assertThat(apps.find(id).orElseThrow().interviewRounds()).isEqualTo(1);
	}

	@Test
	void interviewInviteIncrementsRounds() {
		long id = apps.insert("Acme", "acme", "Analyst", Stage.INTERVIEW, null, null);
		apps.incrementInterviewRounds(id);
		linkThread(id, "t1");
		Classification c = new Classification(Relevance.YES, "Acme", "Analyst", Stage.INTERVIEW,
				List.of(new SuggestedAction("Attend final interview", "2026-11-20T10:00+00:00")), 0.9, "x");

		ProcessOutcome o = processor.process(email("t1"), mail("t1"), c);

		assertThat(o).isEqualTo(ProcessOutcome.UPDATED);
		assertThat(apps.find(id).orElseThrow().interviewRounds()).isEqualTo(2);
		assertThat(history.forApplication(id)).isEmpty();
		assertThat(actions.findForApplication(id)).hasSize(1);
	}

	@Test
	void regressionGoesToReviewAndChangesNothing() {
		long id = apps.insert("Acme", "acme", "Analyst", Stage.INTERVIEW, null, null);
		long act = actions.insert(id, "Attend", null, Stage.INTERVIEW, null);
		linkThread(id, "t1");
		Classification c = new Classification(Relevance.YES, "Acme", "Analyst", Stage.APPLIED,
				List.of(new SuggestedAction("Something new", null)), 0.9, "x");

		ProcessOutcome o = processor.process(email("t1"), mail("t1"), c);

		assertThat(o).isEqualTo(ProcessOutcome.REVIEW);
		assertThat(apps.find(id).orElseThrow().stage()).isEqualTo(Stage.INTERVIEW);
		assertThat(history.forApplication(id)).isEmpty();
		assertThat(actions.findForApplication(id)).singleElement().satisfies(x -> {
			assertThat(x.id()).isEqualTo(act);
			assertThat(x.status()).isEqualTo(ApplicationAction.Status.OPEN);
		});
		assertThat(reviews.findOpen()).singleElement().satisfies(r -> {
			assertThat(r.reason()).isEqualTo(ReviewReason.REGRESSION);
			assertThat(r.applicationId()).isEqualTo(id);
		});
	}

	@Test
	void lockedStageChangeBecomesSuggestion() {
		long id = apps.insert("Acme", "acme", "Analyst", Stage.INTERVIEW, null, null);
		apps.lock(id, "stage");
		linkThread(id, "t1");

		ProcessOutcome o = processor.process(email("t1"), mail("t1"),
				cls(Relevance.YES, "Acme", "Analyst", Stage.REJECTED, 0.95));

		assertThat(o).isEqualTo(ProcessOutcome.REVIEW);
		assertThat(apps.find(id).orElseThrow().stage()).isEqualTo(Stage.INTERVIEW);
		ReviewItem r = reviews.findOpen().get(0);
		assertThat(r.reason()).isEqualTo(ReviewReason.EDITED_FIELD);
		assertThat(r.suggestionJson()).contains("REJECTED");
	}

	@Test
	void closingStageClosesAllOpenActions() {
		long id = apps.insert("Acme", "acme", "Analyst", Stage.INTERVIEW, null, null);
		actions.insert(id, "A", null, Stage.INTERVIEW, null);
		actions.insert(id, "B", null, Stage.ONLINE_TEST, null);
		linkThread(id, "t1");

		ProcessOutcome o = processor.process(email("t1"), mail("t1"),
				new Classification(Relevance.YES, "Acme", "Analyst", Stage.REJECTED,
						List.of(new SuggestedAction("Ignored on closed app", null)), 0.9, "x"));

		assertThat(o).isEqualTo(ProcessOutcome.UPDATED);
		assertThat(apps.find(id).orElseThrow().stage()).isEqualTo(Stage.REJECTED);
		assertThat(actions.findForApplication(id)).hasSize(2)
				.allMatch(x -> x.status() == ApplicationAction.Status.CLOSED);
	}

	@Test
	void duplicateActionNotAddedTwice() {
		Classification c = new Classification(Relevance.YES, "Acme", "Analyst", Stage.ONLINE_TEST,
				List.of(new SuggestedAction("Do the test", "2026-11-07T10:00+00:00")), 0.9, "x");
		processor.process(email("t1"), mail("t1"), c);
		Classification again = new Classification(Relevance.YES, "Acme", "Analyst", Stage.ONLINE_TEST,
				List.of(new SuggestedAction("Do the test", "2026-11-07T11:00+01:00")), 0.9, "x");

		ProcessOutcome o = processor.process(email("t2"), mail("t2"), again);

		assertThat(o).isEqualTo(ProcessOutcome.UPDATED);
		long id = apps.findAll().get(0).id();
		assertThat(actions.findForApplication(id)).hasSize(1);
		assertThat(apps.find(id).orElseThrow().interviewRounds()).isZero();
	}

	@Test
	void ambiguousMatchGoesToReview() {
		long a = apps.insert("Acme", "acme", "Data Placement", Stage.APPLIED, null, null);
		long b = apps.insert("Acme", "acme", "HR Placement", Stage.APPLIED, null, null);

		ProcessOutcome o = processor.process(email("t9"), mail("t9"),
				cls(Relevance.YES, "Acme", "Finance Placement", Stage.ONLINE_TEST, 0.9));

		assertThat(o).isEqualTo(ProcessOutcome.REVIEW);
		ReviewItem r = reviews.findOpen().get(0);
		assertThat(r.reason()).isEqualTo(ReviewReason.AMBIGUOUS_MATCH);
		assertThat(r.suggestionJson()).contains("candidateIds").contains(String.valueOf(a))
				.contains(String.valueOf(b));
		assertThat(apps.findAll()).hasSize(2);
	}

	@Test
	void emailAlwaysLinkedWhenApplicationKnown() {
		long id = apps.insert("Acme", "acme", "Analyst", Stage.INTERVIEW, null, null);
		linkThread(id, "t1");
		EmailRecord e = email("t1");

		processor.process(e, mail("t1"), cls(Relevance.YES, "Acme", "Analyst", Stage.APPLIED, 0.9));

		EmailRecord after = emails.find(e.id()).orElseThrow();
		assertThat(after.applicationId()).isEqualTo(id);
		assertThat(after.status()).isEqualTo(EmailStatus.REVIEW);
	}

	@Test
	void createdInClosedStageGetsNoActions() {
		for (Stage closed : new Stage[] { Stage.REJECTED, Stage.OFFER }) {
			String t = "closed-" + closed;
			Classification c = new Classification(Relevance.YES, "Co " + closed, "Analyst", closed,
					List.of(new SuggestedAction("Reply to offer", "2026-11-07")), 0.9, "x");

			ProcessOutcome o = processor.process(email(t), mail(t), c);

			assertThat(o).isEqualTo(ProcessOutcome.CREATED);
			Application a = apps.findAll().stream().filter(x -> x.stage() == closed).findFirst().orElseThrow();
			assertThat(actions.findForApplication(a.id())).isEmpty();
		}
		assertThat(processor.newActionIds()).isEmpty();
	}

	@Test
	void notSubmittedUsesStandardFinishTextEvenWithModelAction() {
		Classification c = new Classification(Relevance.YES, "Acme", "Analyst", Stage.NOT_SUBMITTED,
				List.of(new SuggestedAction("Don't forget to complete your form", "2026-11-07")), 0.9, "x");

		processor.process(email("t1"), mail("t1"), c);

		long id = apps.findAll().get(0).id();
		assertThat(actions.findForApplication(id)).singleElement().satisfies(x -> {
			assertThat(x.description()).isEqualTo("Finish your Acme application");
			assertThat(x.dueAt()).isEqualTo(OffsetDateTime.parse("2026-11-07T23:59:00Z"));
		});
	}

	@Test
	void repeatedFinishRemindersDoNotDuplicate() {
		Classification first = new Classification(Relevance.YES, "Acme", "Analyst", Stage.NOT_SUBMITTED,
				List.of(new SuggestedAction("Complete your application", "2026-11-07")), 0.9, "x");
		Classification second = new Classification(Relevance.YES, "Acme", "Analyst", Stage.NOT_SUBMITTED,
				List.of(new SuggestedAction("Last chance to submit", "2026-11-07"),
						new SuggestedAction("Later thing", "2026-11-09")), 0.9, "x");
		processor.process(email("t1"), mail("t1"), first);

		processor.process(email("t2"), mail("t2"), second);

		long id = apps.findAll().get(0).id();
		assertThat(actions.findForApplication(id)).hasSize(1);
	}

	@Test
	void olderEmailDoesNotMoveStageBack() {
		Classification invite = new Classification(Relevance.YES, "Acme", "Analyst", Stage.INTERVIEW,
				List.of(new SuggestedAction("Attend interview", "2026-11-20T10:00+00:00")), 0.9, "x");
		EmailRecord newer = email("t-new", Instant.parse("2026-09-10T10:00:00Z"));
		processor.process(newer, mail("t-new", newer.receivedAt()), invite);
		long id = apps.findAll().get(0).id();

		Classification test = new Classification(Relevance.YES, "Acme", "Analyst", Stage.ONLINE_TEST,
				List.of(new SuggestedAction("Complete the online test", "2026-09-08")), 0.9, "x");
		EmailRecord older = email("t-old", Instant.parse("2026-09-01T10:00:00Z"));
		ProcessOutcome o = processor.process(older, mail("t-old", older.receivedAt()), test);

		assertThat(o).isEqualTo(ProcessOutcome.UPDATED);
		assertThat(apps.find(id).orElseThrow().stage()).isEqualTo(Stage.INTERVIEW);
		assertThat(history.forApplication(id)).hasSize(1);
		assertThat(reviews.findOpen()).isEmpty();
		EmailRecord after = emails.find(older.id()).orElseThrow();
		assertThat(after.applicationId()).isEqualTo(id);
		assertThat(after.status()).isEqualTo(EmailStatus.PROCESSED);
		assertThat(actions.findForApplication(id)).singleElement().satisfies(x -> {
			assertThat(x.description()).isEqualTo("Attend interview");
			assertThat(x.status()).isEqualTo(ApplicationAction.Status.OPEN);
		});

		// an older email for the current stage still contributes its to-do
		Classification prep = new Classification(Relevance.YES, "Acme", "Analyst", Stage.INTERVIEW,
				List.of(new SuggestedAction("Send availability", "2026-09-12")), 0.9, "x");
		EmailRecord olderSameStage = email("t-mid", Instant.parse("2026-09-05T10:00:00Z"));
		processor.process(olderSameStage, mail("t-mid", olderSameStage.receivedAt()), prep);

		assertThat(apps.find(id).orElseThrow().stage()).isEqualTo(Stage.INTERVIEW);
		assertThat(actions.findForApplication(id)).extracting(ApplicationAction::description)
				.containsExactlyInAnyOrder("Attend interview", "Send availability");
		assertThat(reviews.findOpen()).isEmpty();
	}

	// ---- helpers ----

	/** Links the thread to the application through an earlier email, so the matcher finds it. */
	private void linkThread(long appId, String thread) {
		jdbc.update("INSERT INTO emails (account, message_id, thread_id, application_id, status, attempts) "
				+ "VALUES ('GMAIL', ?, ?, ?, 'PROCESSED', 0)", "seed-" + thread, thread, appId);
	}

	private EmailRecord email(String thread) {
		return email(thread, Instant.parse("2026-10-29T10:00:00Z"));
	}

	private EmailRecord email(String thread, Instant at) {
		String mid = "msg-" + (++seq);
		MailMessage m = new MailMessage(MailAccount.GMAIL, mid, thread, null, "hr@acme.com", "Subject", at, "body",
				null, Set.of());
		return emails.insertIfNew(m, "dk-" + mid).orElseThrow();
	}

	private static MailMessage mail(String thread) {
		return mail(thread, Instant.parse("2026-10-29T10:00:00Z"));
	}

	private static MailMessage mail(String thread, Instant at) {
		return new MailMessage(MailAccount.GMAIL, "m-" + thread, thread, null, "hr@acme.com", "Subject", at, "body",
				null, Set.of());
	}

	private static Classification cls(Relevance rel, String company, String role, Stage stage, double conf) {
		return new Classification(rel, company, role, stage, List.of(), conf, "test");
	}
}
