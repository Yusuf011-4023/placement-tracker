package com.placementtracker.mail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.placementtracker.config.DataDir;
import com.placementtracker.config.PreFilterProperties;

/**
 * Cheap local check that drops obvious noise before an email reaches the AI. Conservative: when unsure, it lets
 * the email through. Mail from a blocked sender is dropped first; otherwise the email passes when a keyword matches
 * as a whole word (a trailing {@code *} means "any word ending") or the sender is in a known ATS domain.
 */
@Component
public class PreFilter {

	private static final Logger log = LoggerFactory.getLogger(PreFilter.class);

	static final String BLOCKED_SENDERS_FILE = "blocked-senders.txt";

	private static final int BODY_SCAN_CHARS = 4000;

	private final List<Pattern> keywords;
	private final List<String> domains;
	private final List<String> blockedAddresses;
	private final List<String> blockedDomains;

	@Autowired
	public PreFilter(PreFilterProperties properties, DataDir dataDir) {
		this(properties, dataDir.resolve(BLOCKED_SENDERS_FILE));
	}

	/** Reads extra blocked senders from {@code blockedSendersFile}; a missing or unreadable file adds none. */
	PreFilter(PreFilterProperties properties, Path blockedSendersFile) {
		this.keywords = properties.keywords().stream().map(PreFilter::compile).toList();
		this.domains = Stream.concat(properties.atsDomains().stream(), properties.extraDomains().stream())
			.map(d -> d.toLowerCase(Locale.ROOT).trim())
			.toList();
		List<String> entries = new ArrayList<>(properties.blockedSenders());
		entries.addAll(readLines(blockedSendersFile));
		List<String> addresses = new ArrayList<>();
		List<String> blockedDomainList = new ArrayList<>();
		for (String raw : entries) {
			String entry = raw == null ? "" : raw.replace("﻿", "").trim().toLowerCase(Locale.ROOT);
			if (entry.isEmpty() || entry.startsWith("#")) {
				continue;
			}
			if (entry.startsWith("@")) {
				entry = entry.substring(1).trim();
				if (entry.isEmpty()) {
					continue;
				}
			}
			List<String> target = entry.contains("@") ? addresses : blockedDomainList;
			target.add(entry);
		}
		this.blockedAddresses = List.copyOf(addresses);
		this.blockedDomains = List.copyOf(blockedDomainList);
	}

	public boolean shouldClassify(MailMessage m) {
		if (senderBlocked(m.from())) {
			return false;
		}
		return containsKeyword(m.subject(), m.bodyText()) || senderInKnownDomain(m.from());
	}

	/** Whole-word, case-insensitive pattern; a trailing {@code *} lets the word carry any ending. */
	private static Pattern compile(String keyword) {
		String k = keyword.toLowerCase(Locale.ROOT).trim();
		boolean prefix = k.endsWith("*");
		if (prefix) {
			k = k.substring(0, k.length() - 1).trim();
		}
		String tail = prefix ? "" : "(?![\\p{L}\\p{N}])";
		return Pattern.compile("(?<![\\p{L}\\p{N}])" + Pattern.quote(k) + tail);
	}

	private static List<String> readLines(Path file) {
		try {
			return Files.readAllLines(file, StandardCharsets.UTF_8);
		}
		catch (NoSuchFileException e) {
			return List.of();
		}
		catch (IOException | RuntimeException e) {
			log.warn("Could not read {}; ignoring it: {}", file, e.getMessage());
			return List.of();
		}
	}

	private boolean senderBlocked(String from) {
		String address = SenderAddress.of(from);
		if (address.isEmpty()) {
			return false;
		}
		if (blockedAddresses.contains(address)) {
			return true;
		}
		String domain = SenderAddress.domainOf(from);
		return !domain.isEmpty() && blockedDomains.stream().anyMatch(d -> domain.equals(d) || domain.endsWith("." + d));
	}

	private boolean containsKeyword(String subject, String body) {
		String scanned = body == null ? "" : body.substring(0, Math.min(BODY_SCAN_CHARS, body.length()));
		String haystack = lower(subject) + "\n" + lower(scanned);
		return keywords.stream().anyMatch(p -> p.matcher(haystack).find());
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
