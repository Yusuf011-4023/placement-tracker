package com.placementtracker.sync;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.placementtracker.mail.EmailRecord;
import com.placementtracker.mail.EmailRepository;
import com.placementtracker.mail.EmailStatus;
import com.placementtracker.review.ReviewReason;
import com.placementtracker.review.ReviewRepository;

/** Records an email's classification failure atomically, so a half-written failure can never lose the email. */
@Component
public class FailureRecorder {

	private final EmailRepository emails;
	private final ReviewRepository reviews;

	public FailureRecorder(EmailRepository emails, ReviewRepository reviews) {
		this.emails = emails;
		this.reviews = reviews;
	}

	/**
	 * Records the error. When {@code countAttempt} is false (outage, bad key) the email just stays FAILED. Otherwise
	 * the attempt is counted and, at {@code maxAttempts}, it goes to an AI_FAILED review. Returns true when a review
	 * item was opened.
	 */
	@Transactional
	public boolean record(EmailRecord rec, String error, boolean countAttempt, int maxAttempts) {
		if (!countAttempt) {
			emails.recordError(rec.id(), error);
			emails.setStatus(rec.id(), EmailStatus.FAILED);
			return false;
		}
		int attempts = emails.recordFailure(rec.id(), error);
		if (attempts >= maxAttempts) {
			return escalate(rec);
		}
		emails.setStatus(rec.id(), EmailStatus.FAILED);
		return false;
	}

	/** Sends an email that can no longer be retried to the "Please check" queue. */
	@Transactional
	public boolean escalate(EmailRecord rec) {
		reviews.open(rec.id(), rec.applicationId(), ReviewReason.AI_FAILED, "{}");
		emails.setStatus(rec.id(), EmailStatus.REVIEW);
		return true;
	}
}
