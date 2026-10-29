package com.placementtracker.mail;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.regex.Pattern;

/** Builds the key used to recognise the same email arriving more than once. */
public final class DedupeKeys {

	private static final long BUCKET_SECONDS = 10 * 60;
	private static final Pattern REPLY_PREFIX = Pattern.compile("^\s*(?:(?:re|fwd|fw)\s*:\s*)+",
			Pattern.CASE_INSENSITIVE);

	private DedupeKeys() {
	}

	public static String of(MailMessage m) {
		String imid = m.internetMessageId();
		if (imid != null && !imid.isBlank()) {
			return sha256("imid|" + imid.trim());
		}
		String sender = SenderAddress.of(m.from());
		String subject = normaliseSubject(m.subject());
		String bucket = m.receivedAt() == null ? "" : Long.toString(m.receivedAt().getEpochSecond() / BUCKET_SECONDS);
		return sha256("fallback|" + sender + "|" + subject + "|" + bucket);
	}

	private static String normaliseSubject(String subject) {
		if (subject == null) {
			return "";
		}
		String s = REPLY_PREFIX.matcher(subject).replaceFirst("");
		return s.trim().replaceAll("\s+", " ").toLowerCase(Locale.ROOT);
	}

	private static String sha256(String input) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		}
		catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 not available", e);
		}
	}
}
