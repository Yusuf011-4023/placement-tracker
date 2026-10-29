package com.placementtracker.matching;

public final class CompanyNames {

	private CompanyNames() {
	}

	public static String key(String name) {
		return name == null ? "" : name.trim().toLowerCase();
	}
}
