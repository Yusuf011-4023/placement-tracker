package com.placementtracker.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

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
	void retryOnOtherReasonIs409() throws Exception {
		long review = reviews.open(email().id(), null, ReviewReason.LOW_CONFIDENCE, "{}");

		mvc.perform(own(post("/api/review/" + review + "/retry"))).andExpect(status().isConflict());
	}
}
