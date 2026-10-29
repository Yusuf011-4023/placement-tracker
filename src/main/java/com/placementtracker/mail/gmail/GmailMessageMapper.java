package com.placementtracker.mail.gmail;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.api.services.gmail.model.Message;
import com.google.api.services.gmail.model.MessagePart;
import com.google.api.services.gmail.model.MessagePartHeader;
import com.placementtracker.mail.HtmlToText;
import com.placementtracker.mail.MailAccount;
import com.placementtracker.mail.MailMessage;

/** Maps a Gmail API message (format FULL) to the app's {@link MailMessage}. */
public final class GmailMessageMapper {

	private static final Map<String, String> LABELS = Map.of("SPAM", "SPAM", "CATEGORY_PROMOTIONS", "PROMOTIONS",
			"CATEGORY_SOCIAL", "SOCIAL", "CATEGORY_UPDATES", "UPDATES");

	private static final Pattern CHARSET = Pattern.compile("charset=\"?([^\";\s]+)\"?", Pattern.CASE_INSENSITIVE);

	private GmailMessageMapper() {
	}

	public static MailMessage toMailMessage(Message m) {
		MessagePart payload = m.getPayload();
		long internal = m.getInternalDate() != null ? m.getInternalDate() : 0L;
		return new MailMessage(MailAccount.GMAIL, m.getId(), m.getThreadId(), header(payload, "Message-ID"),
				header(payload, "From"), header(payload, "Subject"), Instant.ofEpochMilli(internal), body(payload),
				"https://mail.google.com/mail/u/0/#all/" + m.getId(), labels(m.getLabelIds()));
	}

	private static Set<String> labels(List<String> ids) {
		Set<String> out = new LinkedHashSet<>();
		if (ids != null) {
			for (String id : ids) {
				String mapped = LABELS.get(id);
				if (mapped != null) {
					out.add(mapped);
				}
			}
		}
		return out;
	}

	private static String header(MessagePart part, String name) {
		if (part == null || part.getHeaders() == null) {
			return null;
		}
		for (MessagePartHeader h : part.getHeaders()) {
			if (name.equalsIgnoreCase(h.getName())) {
				return h.getValue();
			}
		}
		return null;
	}

	private static String body(MessagePart payload) {
		if (payload == null) {
			return "";
		}
		String plain = find(payload, "text/plain");
		if (plain != null) {
			return plain;
		}
		String html = find(payload, "text/html");
		return html != null ? HtmlToText.convert(html) : "";
	}

	/** Depth-first search for the first non-attachment part of the given type with data. */
	private static String find(MessagePart part, String mimeType) {
		boolean attachment = (part.getFilename() != null && !part.getFilename().isEmpty())
				|| (part.getBody() != null && part.getBody().getAttachmentId() != null);
		if (attachment) {
			return null;
		}
		if (mimeType.equalsIgnoreCase(part.getMimeType()) && part.getBody() != null
				&& part.getBody().getData() != null) {
			return decode(part);
		}
		if (part.getParts() != null) {
			for (MessagePart child : part.getParts()) {
				String found = find(child, mimeType);
				if (found != null) {
					return found;
				}
			}
		}
		return null;
	}

	private static String decode(MessagePart part) {
		byte[] bytes = part.getBody().decodeData();
		Charset cs = StandardCharsets.UTF_8;
		String contentType = header(part, "Content-Type");
		if (contentType != null) {
			Matcher matcher = CHARSET.matcher(contentType);
			if (matcher.find()) {
				try {
					cs = Charset.forName(matcher.group(1));
				}
				catch (RuntimeException ignored) {
					// unknown charset: keep UTF-8
				}
			}
		}
		return new String(bytes, cs);
	}
}
