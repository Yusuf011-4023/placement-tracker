package com.placementtracker.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.placementtracker.application.ApplicationAction;
import com.placementtracker.application.Stage;
import com.placementtracker.mail.EmailRepository;
import com.placementtracker.mail.MailAccount;
import com.placementtracker.mail.MailMessage;
import com.placementtracker.review.ReviewReason;
import com.placementtracker.review.ReviewRepository;

class ApplicationControllerTest extends ApiTestBase {

	@Autowired EmailRepository emails;
	@Autowired ReviewRepository reviews;

	@Test
	void listApplicationsIncludesNotSubmittedAndClosed() throws Exception {
		app("Zeta", Stage.REJECTED);
		app("alpha", Stage.NOT_SUBMITTED);
		app("Mid", Stage.APPLIED);

		mvc.perform(get("/api/applications").header("Host", "localhost:8765"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$", hasSize(3)))
				.andExpect(jsonPath("$[0].company", is("alpha")))
				.andExpect(jsonPath("$[0].stage", is("NOT_SUBMITTED")))
				.andExpect(jsonPath("$[1].company", is("Mid")))
				.andExpect(jsonPath("$[2].stage", is("REJECTED")));
		mvc.perform(get("/api/applications").header("Host", "evil.example"))
				.andExpect(status().isForbidden());
	}

	@Test
	void patchStageLocksAndRecordsUserHistory() throws Exception {
		long a = app("Acme", Stage.APPLIED);
		long old = action(a, "Old to-do", 10, Stage.APPLIED);

		mvc.perform(own(patch("/api/applications/" + a)).content("{\"stage\":\"ONLINE_TEST\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.stage", is("ONLINE_TEST")))
				.andExpect(jsonPath("$.lockedFields", hasItem("stage")));

		assertThat(apps.find(a).orElseThrow().stage()).isEqualTo(Stage.ONLINE_TEST);
		assertThat(apps.find(a).orElseThrow().lockedFields()).contains("stage");
		var changes = history.forApplication(a);
		assertThat(changes).hasSize(1);
		assertThat(changes.get(0).source()).isEqualTo("USER");
		assertThat(changes.get(0).fromStage()).isEqualTo(Stage.APPLIED);
		assertThat(changes.get(0).toStage()).isEqualTo(Stage.ONLINE_TEST);
		assertThat(actions.find(old).orElseThrow().status()).isEqualTo(ApplicationAction.Status.CLOSED);
	}

	@Test
	void patchLocksOtherChangedFieldsOnly() throws Exception {
		long a = app("Acme", Stage.APPLIED);

		mvc.perform(own(patch("/api/applications/" + a))
				.content("{\"company\":\"Acme\",\"role\":\"Analyst\",\"notes\":\"hi\",\"appliedOn\":\"2026-10-01\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.role", is("Analyst")))
				.andExpect(jsonPath("$.appliedOn", is("2026-10-01")));

		assertThat(apps.find(a).orElseThrow().lockedFields()).isEqualTo(Set.of("role", "notes", "appliedOn"));
	}

	@Test
	void patchToClosedStageClosesAllOpenActions() throws Exception {
		long a = app("Acme", Stage.INTERVIEW);
		long x = action(a, "Mock", 10, Stage.INTERVIEW);
		long y = action(a, "Stray", 10, Stage.APPLIED);

		mvc.perform(own(patch("/api/applications/" + a)).content("{\"stage\":\"REJECTED\"}"))
				.andExpect(status().isOk());

		assertThat(actions.find(x).orElseThrow().status()).isEqualTo(ApplicationAction.Status.CLOSED);
		assertThat(actions.find(y).orElseThrow().status()).isEqualTo(ApplicationAction.Status.CLOSED);
	}

	@Test
	void firstEntryToInterviewSetsRoundsToOne() throws Exception {
		long a = app("Acme", Stage.APPLIED);

		mvc.perform(own(patch("/api/applications/" + a)).content("{\"stage\":\"INTERVIEW\"}"))
				.andExpect(jsonPath("$.interviewRounds", is(1)));
		mvc.perform(own(patch("/api/applications/" + a)).content("{\"stage\":\"ASSESSMENT_CENTRE\"}"));
		mvc.perform(own(patch("/api/applications/" + a)).content("{\"stage\":\"INTERVIEW\"}"))
				.andExpect(jsonPath("$.interviewRounds", is(1)));
	}

	@Test
	void createApplicationRecordsUserHistory() throws Exception {
		mvc.perform(own(post("/api/applications")).content(
				"{\"company\":\"Newco\",\"role\":\"Intern\",\"stage\":\"APPLIED\",\"appliedOn\":\"2026-10-20\",\"notes\":\"n\"}"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.company", is("Newco")))
				.andExpect(jsonPath("$.history[0].source", is("USER")))
				.andExpect(jsonPath("$.history[0].fromStage", nullValue()))
				.andExpect(jsonPath("$.history[0].toStage", is("APPLIED")));
	}

	@Test
	void detailIncludesActionsAndHistoryWithEmail() throws Exception {
		long a = app("Acme", Stage.APPLIED);
		action(a, "Do it", 10, Stage.APPLIED);
		var rec = emails.insertIfNew(new MailMessage(MailAccount.GMAIL, "m1", "t1", "<m1@x>", "jobs@acme.com",
				"Your application", Instant.parse("2026-10-01T10:00:00Z"), "body", "http://mail/m1", Set.of()), "k1")
				.orElseThrow();
		history.record(a, null, Stage.APPLIED, "EMAIL", rec.id());

		mvc.perform(get("/api/applications/" + a).header("Host", "localhost:8765"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.actions", hasSize(1)))
				.andExpect(jsonPath("$.actions[0].description", is("Do it")))
				.andExpect(jsonPath("$.history[0].email.subject", is("Your application")))
				.andExpect(jsonPath("$.history[0].email.webLink", is("http://mail/m1")))
				.andExpect(jsonPath("$.history[0].source", is("EMAIL")));
	}

	@Test
	void addActionAndMerge() throws Exception {
		long a = app("Acme", Stage.APPLIED);
		long b = app("Acme Ltd", Stage.APPLIED);
		mvc.perform(own(post("/api/applications/" + b + "/actions"))
				.content("{\"description\":\"Call them\",\"dueAt\":\"2026-11-01T09:00:00Z\"}"))
				.andExpect(status().isCreated());

		mvc.perform(own(post("/api/applications/" + a + "/merge")).content("{\"sourceId\":" + b + "}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.actions", hasSize(1)));
		assertThat(apps.find(b)).isEmpty();
	}

	@Test
	void addActionToClosedApplicationIs409() throws Exception {
		long a = app("Acme", Stage.REJECTED);

		mvc.perform(own(post("/api/applications/" + a + "/actions")).content("{\"description\":\"Call\"}"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error").isString());
		assertThat(actions.findOpen()).isEmpty();
	}

	@Test
	void errorMappingReturns400And409() throws Exception {
		long a = app("Acme", Stage.APPLIED);
		// 400: unknown stage, merge into itself, unknown review item, malformed JSON
		mvc.perform(own(patch("/api/applications/" + a)).content("{\"stage\":\"NOPE\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error").isString());
		mvc.perform(own(post("/api/applications/" + a + "/merge")).content("{\"sourceId\":" + a + "}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error").isString());
		mvc.perform(own(post("/api/review/999/dismiss"))).andExpect(status().isBadRequest());
		mvc.perform(own(patch("/api/applications/" + a)).content("{not json"))
				.andExpect(status().isBadRequest());
		// 409: confirming an item with nothing to confirm
		long review = reviews.open(null, null, ReviewReason.AI_FAILED, null);
		mvc.perform(own(post("/api/review/" + review + "/confirm")))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error").isString());
		// 404: missing entity
		mvc.perform(get("/api/applications/9999").header("Host", "localhost:8765"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error").isString());
		mvc.perform(own(patch("/api/applications/9999")).content("{\"notes\":\"x\"}"))
				.andExpect(status().isNotFound());
	}

	@Test
	void pleaseCheckShowsOpenReviewItemWithSuggestion() throws Exception {
		long review = reviews.open(null, null, ReviewReason.LOW_CONFIDENCE,
				"{\"classification\":{\"relevance\":\"YES\",\"company\":\"Acme\",\"stage\":\"APPLIED\"}}");

		mvc.perform(get("/api/dashboard").header("Host", "localhost:8765"))
				.andExpect(jsonPath("$.pleaseCheck", hasSize(1)))
				.andExpect(jsonPath("$.pleaseCheck[0].reviewId", is((int) review)))
				.andExpect(jsonPath("$.pleaseCheck[0].reason", is("LOW_CONFIDENCE")))
				.andExpect(jsonPath("$.pleaseCheck[0].suggestion.classification.company", is("Acme")));
	}
}
