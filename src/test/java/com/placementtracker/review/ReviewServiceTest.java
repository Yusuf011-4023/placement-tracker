package com.placementtracker.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Instant;
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
import com.placementtracker.processing.EmailProcessor;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ReviewServiceTest {

	@TempDir
	static Path tmp;

	@DynamicPropertySource
	static void props(DynamicPropertyRegistry registry) {
		registry.add("tracker.data-dir", () -> tmp.toString());
	}

	@Autowired ReviewService service;
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
	void confirmCreatesApplicationFromLowConfidenceItem() {
		EmailRecord e = email("t1");
		processor.process(e, mail("t1"), cls("Acme", Stage.ONLINE_TEST, 0.5, "Complete the online test"));
		ReviewItem item = reviews.findOpen().get(0);
		assertThat(item.reason()).isEqualTo(ReviewReason.LOW_CONFIDENCE);

		service.confirm(item.id(), null);

		Application app = apps.findAll().get(0);
		assertThat(app.company()).isEqualTo("Acme");
		assertThat(app.stage()).isEqualTo(Stage.ONLINE_TEST);
		assertThat(actions.findForApplication(app.id())).extracting(ApplicationAction::description)
				.containsExactly("Complete the online test");
		assertThat(history.forApplication(app.id())).extracting(StageChange::source).containsExactly("USER");
		EmailRecord after = emails.find(e.id()).orElseThrow();
		assertThat(after.status()).isEqualTo(EmailStatus.PROCESSED);
		assertThat(after.applicationId()).isEqualTo(app.id());
		assertThat(reviews.findOpen()).isEmpty();
	}

	@Test
	void confirmAppliesRegressionWhenOwnerAccepts() {
		processor.process(email("t1"), mail("t1"), cls("Acme", Stage.INTERVIEW, 0.9));
		long appId = apps.findAll().get(0).id();
		processor.process(email("t1"), mail("t1"), cls("Acme", Stage.APPLIED, 0.9));
		ReviewItem item = reviews.findOpen().get(0);
		assertThat(item.reason()).isEqualTo(ReviewReason.REGRESSION);

		service.confirm(item.id(), null);

		assertThat(apps.find(appId).orElseThrow().stage()).isEqualTo(Stage.APPLIED);
		assertThat(history.forApplication(appId)).last().satisfies(h -> {
			assertThat(h.source()).isEqualTo("USER");
			assertThat(h.fromStage()).isEqualTo(Stage.INTERVIEW);
			assertThat(h.toStage()).isEqualTo(Stage.APPLIED);
		});
		assertThat(reviews.findOpen()).isEmpty();
	}

	@Test
	void confirmEditedFieldSuggestionUpdatesLockedStage() {
		processor.process(email("t1"), mail("t1"), cls("Acme", Stage.INTERVIEW, 0.9));
		long appId = apps.findAll().get(0).id();
		apps.lock(appId, "stage");
		processor.process(email("t1"), mail("t1"), cls("Acme", Stage.REJECTED, 0.9));
		ReviewItem item = reviews.findOpen().get(0);
		assertThat(item.reason()).isEqualTo(ReviewReason.EDITED_FIELD);

		service.confirm(item.id(), null);

		Application app = apps.find(appId).orElseThrow();
		assertThat(app.stage()).isEqualTo(Stage.REJECTED);
		assertThat(app.lockedFields()).contains("stage");
		assertThat(reviews.findOpen()).isEmpty();
	}

	@Test
	void dismissMarksEmailNotPlacement() {
		EmailRecord e = email("t1");
		processor.process(e, mail("t1"), cls("Acme", Stage.APPLIED, 0.4));
		ReviewItem item = reviews.findOpen().get(0);

		service.dismissNotPlacement(item.id());

		assertThat(emails.find(e.id()).orElseThrow().status()).isEqualTo(EmailStatus.NOT_PLACEMENT);
		assertThat(apps.findAll()).isEmpty();
		assertThat(reviews.findOpen()).isEmpty();
	}

	@Test
	void attachLinksEmailAndAddsActions() {
		processor.process(email("t1"), mail("t1"), cls("Acme", Stage.APPLIED, 0.9));
		long appId = apps.findAll().get(0).id();
		EmailRecord e = email("t2");
		processor.process(e, mail("t2"), cls("Other Co", Stage.ONLINE_TEST, 0.5, "Do the test"));
		ReviewItem item = reviews.findOpen().get(0);

		service.attachTo(item.id(), appId);

		assertThat(apps.findAll()).hasSize(1);
		assertThat(apps.find(appId).orElseThrow().stage()).isEqualTo(Stage.ONLINE_TEST);
		assertThat(emails.find(e.id()).orElseThrow().applicationId()).isEqualTo(appId);
		assertThat(actions.findForApplication(appId)).extracting(ApplicationAction::description)
				.containsExactly("Do the test");
		assertThat(reviews.findOpen()).isEmpty();
	}

	@Test
	void attachRegressionLinksButLeavesNewRegressionItem() {
		processor.process(email("t1"), mail("t1"), cls("Acme", Stage.INTERVIEW, 0.9));
		long appId = apps.findAll().get(0).id();
		EmailRecord e = email("t2");
		processor.process(e, mail("t2"), cls("Other Co", Stage.APPLIED, 0.5, "Do a thing"));
		ReviewItem item = reviews.findOpen().get(0);

		service.attachTo(item.id(), appId);

		assertThat(apps.find(appId).orElseThrow().stage()).isEqualTo(Stage.INTERVIEW);
		assertThat(actions.findForApplication(appId)).isEmpty();
		assertThat(emails.find(e.id()).orElseThrow().applicationId()).isEqualTo(appId);
		assertThat(reviews.findOpen()).singleElement().satisfies(r -> {
			assertThat(r.reason()).isEqualTo(ReviewReason.REGRESSION);
			assertThat(r.applicationId()).isEqualTo(appId);
			assertThat(r.id()).isNotEqualTo(item.id());
		});
	}

	@Test
	void resolvedItemsLeaveOpenListAndCannotBeReused() {
		processor.process(email("t1"), mail("t1"), cls("Acme", Stage.APPLIED, 0.4));
		long id = reviews.findOpen().get(0).id();

		service.confirm(id, null);

		assertThat(reviews.findOpen()).isEmpty();
		assertThatThrownBy(() -> service.confirm(id, null)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> service.dismissNotPlacement(id)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> service.confirm(9999, null)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void aiFailedItemNeedsEditBeforeConfirm() {
		EmailRecord e = email("t1");
		long id = reviews.open(e.id(), null, ReviewReason.AI_FAILED, "{}");

		assertThatThrownBy(() -> service.confirm(id, null)).isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("edit first");
		assertThat(reviews.findOpen()).hasSize(1);

		service.confirm(id, cls("Acme", Stage.APPLIED, 1.0));

		assertThat(apps.findAll()).singleElement().satisfies(a -> assertThat(a.company()).isEqualTo("Acme"));
		assertThat(reviews.findOpen()).isEmpty();
	}

	private EmailRecord email(String thread) {
		String mid = "msg-" + (++seq);
		MailMessage m = new MailMessage(MailAccount.GMAIL, mid, thread, null, "hr@acme.com", "Subject",
				Instant.parse("2026-10-29T10:00:00Z"), "body", null, Set.of());
		return emails.insertIfNew(m, "dk-" + mid).orElseThrow();
	}

	private static MailMessage mail(String thread) {
		return new MailMessage(MailAccount.GMAIL, "m-" + thread, thread, null, "hr@acme.com", "Subject",
				Instant.parse("2026-10-29T10:00:00Z"), "body", null, Set.of());
	}

	private static Classification cls(String company, Stage stage, double conf, String... actionTexts) {
		List<SuggestedAction> acts = java.util.Arrays.stream(actionTexts)
				.map(t -> new SuggestedAction(t, "2026-11-07")).toList();
		return new Classification(Relevance.YES, company, "Analyst", stage, acts, conf, "test");
	}
}
