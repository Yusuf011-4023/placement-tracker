package com.placementtracker.mail;

import java.time.Instant;

public record EmailRecord(long id, MailAccount account, String messageId, String threadId, String dedupeKey,
		String from, String subject, Instant receivedAt, String webLink, Long applicationId, EmailStatus status,
		int attempts) {
}
