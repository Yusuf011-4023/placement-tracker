package com.placementtracker.mail;

import java.time.Instant;
import java.util.Set;

public record MailMessage(MailAccount account, String messageId, String threadId, String internetMessageId,
		String from, String subject, Instant receivedAt, String bodyText, String webLink, Set<String> labels) {
}
