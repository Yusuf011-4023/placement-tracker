package com.placementtracker.mail;

import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import org.springframework.stereotype.Component;

import com.placementtracker.config.PreFilterProperties;

/**
 * Cheap local check that drops obvious noise before an email reaches the AI. Conservative: when unsure, it lets
 * the email through.
 */
@Component
public class PreFilter {

	private static final int BODY_SCAN_CHARS = 4000;

	private final List<String> keywords;
	private final List<String> domains;

	public PreFilter(PreFilterProperties properties) {
		this.keywords = properties.keywords().stream().map(k -> k.toLowerCase(Locale.ROOT).trim()).toList();
		this.domains = Stream.concat(properties.atsDomains().stream(), properties.extraDomains().stream())
			.map(d -> d.toLowerCase(Locale.ROOT).trim())
			.toList();
	}

	public boolean shouldClassify(MailMessage m) {
		return containsKeyword(m.subject(), m.bodyText()) || senderInKnownDomain(m.from());
	}

	private boolean containsKeyword(String subject, String body) {
		String scanned = body == null ? "" : body.substring(0, Math.min(BODY_SCAN_CHARS, body.length()));
		String haystack = lower(subject) + "\n" + lower(scanned);
		return keywords.stream().anyMatch(haystack::contains);
	}

	private boolean senderInKnownDomain(String from) {
		String domain = SenderAddress.domainOf(from);
		if (domain.isEmpty()) {
			return false;
		}
		return domains.stream().anyMatch(d -> domain.equals(d) || domain.endsWith("." + d));
	}

	private static String lower(String s) {
		return s == null ? "" : s.toLowerCase(Locale.ROOT);
	}
}
