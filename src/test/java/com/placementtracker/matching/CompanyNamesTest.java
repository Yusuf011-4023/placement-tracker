package com.placementtracker.matching;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CompanyNamesTest {

	@Test
	void legalSuffixesIgnored() {
		String key = CompanyNames.key("Northwind Bank plc");
		assertThat(CompanyNames.key("Northwind Bank Ltd")).isEqualTo(key);
		assertThat(CompanyNames.key("The Northwind Bank")).isEqualTo(key);
	}

	@Test
	void punctuationAndCaseIgnored() {
		assertThat(CompanyNames.key("Brightline-Energy UK")).isEqualTo(CompanyNames.key("brightline energy"));
		assertThat(CompanyNames.key("Brightline-Energy UK")).isEqualTo("brightline energy");
	}

	@Test
	void nullBecomesEmpty() {
		assertThat(CompanyNames.key(null)).isEmpty();
	}
}
