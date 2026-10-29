package com.placementtracker.matching;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class CompanyNames {

	private static final Set<String> SUFFIXES = Set.of("plc", "ltd", "limited", "llp", "inc", "llc", "group", "uk",
			"holdings");

	private CompanyNames() {
	}

	/**
	 * Normalised comparison key: lowercase, punctuation removed, a leading "the" and trailing legal/corporate
	 * suffixes dropped, spaces collapsed. A name made only of such words keeps them so it never becomes empty.
	 */
	public static String key(String name) {
		if (name == null) {
			return "";
		}
		String cleaned = name.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
		if (cleaned.isEmpty()) {
			return "";
		}
		List<String> tokens = new ArrayList<>(List.of(cleaned.split(" ")));
		if (tokens.size() > 1 && tokens.get(0).equals("the")) {
			tokens.remove(0);
		}
		while (tokens.size() > 1 && SUFFIXES.contains(tokens.get(tokens.size() - 1))) {
			tokens.remove(tokens.size() - 1);
		}
		return String.join(" ", tokens);
	}
}
