package com.placementtracker.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.placementtracker.application.Stage;
import com.placementtracker.mail.EmailRecord;
import com.placementtracker.mail.EmailRepository;
import com.placementtracker.mail.EmailStatus;
import com.placementtracker.mail.MailAccount;
import com.placementtracker.mail.MailMessage;
import com.placementtracker.review.ReviewReason;
import com.placementtracker.review.ReviewRepository;

class ReviewControllerTest extends ApiTestBase {

	@Autowired EmailRepository emails;
	@Autowired ReviewRepository reviews;

	EmailRecord email() {
		String id = "m-" + UUID.randomUUID();
		MailMessage m = new MailMessage(MailAccount.GMAIL, id, "t-" + id, null, "hr@acme.com", "Your application",
				Instant.parse("2026-10-20T10:00:00Z"), "body", "https://mail.google.com/mail/u/0/#all/" + id,
				Set.of());
		return emails.insertIfNew(m, "dk-" + id).orElseThrow();
	}

	@Test
	void retryPutsAiFailedEmailBackInTheQueue() throws Exception {
		EmailRecord e = email();
		jdbc.update("UPDATE emails SET status = 'REVIEW', attempts = 3 WHERE id = ?", e.id());
		long review = reviews.open(e.id(), null, ReviewReason.AI_FAILED, "{}");

		mvc.perform(own(post("/api/review/" + review + "/retry"))).andExpect(status().isOk());

		// the triggered sync may already be classifying it; either way it is out of REVIEW with fresh attempts
		EmailRecord after = emails.find(e.id()).orElseThrow();
		assertThat(after.status()).isNotEqualTo(EmailStatus.REVIEW);
		assertThat(after.attempts()).isZero();
		assertThat(reviews.findOpen()).isEmpty();
	}

	@Test
	void confirmWithEditCreatesTheEditedApplication() throws Exception {
		EmailRecord e = email();
		long review = reviews.open(e.id(), null, ReviewReason.LOW_CONFIDENCE,
				"{\"classification\":{\"relevance\":\"UNSURE\",\"company\":\"Acm\",\"stage\":\"APPLIED\"}}");

		mvc.perform(own(post("/api/review/" + review + "/confirm")).content("""
				{"relevance":"YES","company":"Acme","role":"Analyst","stage":"ONLINE_TEST","actions":[],
				 "confidence":1.0,"reason":"Confirmed by you"}"""))
				.andExpect(status().isOk());

		assertThat(apps.findAll()).singleElement().satisfies(a -> {
			assertThat(a.company()).isEqualTo("Acme");
			assertThat(a.stage()).isEqualTo(Stage.ONLINE_TEST);
		});
		assertThat(emails.find(e.id()).orElseThrow().status()).isEqualTo(EmailStatus.PROCESSED);
		assertThat(reviews.findOpen()).isEmpty();
	}

	@Test
	void attachLinksTheEmailAndAppliesTheSuggestion() throws Exception {
		long appId = app("Acme", Stage.APPLIED);
		EmailRecord e = email();
		long review = reviews.open(e.id(), null, ReviewReason.AMBIGUOUS_MATCH,
				"{\"classification\":{\"relevance\":\"YES\",\"company\":\"Acme\",\"stage\":\"INTERVIEW\","
						+ "\"confidence\":0.9},\"candidateIds\":[" + appId + "]}");

		mvc.perform(own(post("/api/review/" + review + "/attach")).content("{\"applicationId\":" + appId + "}"))
				.andExpect(status().isOk());

		assertThat(apps.find(appId).orElseThrow().stage()).isEqualTo(Stage.INTERVIEW);
		assertThat(emails.find(e.id()).orElseThrow().applicationId()).isEqualTo(appId);
		assertThat(reviews.findOpen()).isEmpty();
	}

	@Test
	void pleaseCheckCarriesCandidatesMatchedStageAndEmailLink() throws Exception {
		long acme = app("Acme", Stage.ONLINE_TEST);
		long other = app("Acme Group", Stage.APPLIED);
		EmailRecord e = email();
		reviews.open(e.id(), null, ReviewReason.AMBIGUOUS_MATCH,
				"{\"classification\":{\"company\":\"Acme\",\"stage\":\"INTERVIEW\"},\"candidateIds\":["
						+ acme + "," + other + "]}");
		EmailRecord e2 = email();
		reviews.open(e2.id(), acme, ReviewReason.LOW_CONFIDENCE,
				"{\"classification\":{\"company\":\"Acme\",\"stage\":\"INTERVIEW\"},\"applicationId\":" + acme
						+ "}");

		mvc.perform(get("/api/dashboard").header("Host", "localhost:8765"))
				.andExpect(jsonPath("$.pleaseCheck[0].candidateIds[0]", is((int) acme)))
				.andExpect(jsonPath("$.pleaseCheck[0].candidateIds[1]", is((int) other)))
				.andExpect(jsonPath("$.pleaseCheck[0].webLink", startsWith("https://mail.google.com/")))
				.andExpect(jsonPath("$.pleaseCheck[0].applicationStage").value(nullValue()))
				.andExpect(jsonPath("$.pleaseCheck[1].applicationId", is((int) acme)))
				.andExpect(jsonPath("$.pleaseCheck[1].applicationCompany", is("Acme")))
				.andExpect(jsonPath("$.pleaseCheck[1].applicationStage", is("ONLINE_TEST")));
	}

	@Test
	void retryOnOtherReasonIs409() throws Exception {
		long review = reviews.open(email().id(), null, ReviewReason.LOW_CONFIDENCE, "{}");

		mvc.perform(own(post("/api/review/" + review + "/retry"))).andExpect(status().isConflict());
	}
}
