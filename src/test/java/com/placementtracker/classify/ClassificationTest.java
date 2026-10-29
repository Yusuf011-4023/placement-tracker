package com.placementtracker.classify;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.placementtracker.application.Stage;

class ClassificationTest {

	private final Classification c = new Classification(Relevance.YES, "Northwind Bank", "Placement Analyst",
			Stage.ONLINE_TEST, List.of(), 0.9, "r");

	@Test
	void dateOnlyDueIsEndOfDayLondon() {
		assertThat(c.dueAtOf(new SuggestedAction("x", "2026-11-07")))
				.isEqualTo(OffsetDateTime.parse("2026-11-07T23:59:00+00:00"));
		// BST: offset is +01:00
		assertThat(c.dueAtOf(new SuggestedAction("x", "2026-07-07")))
				.isEqualTo(OffsetDateTime.parse("2026-07-07T23:59:00+01:00"));
	}

	@Test
	void dateTimeWithOffsetKept() {
		assertThat(c.dueAtOf(new SuggestedAction("x", "2026-10-30T17:00+01:00")))
				.isEqualTo(OffsetDateTime.parse("2026-10-30T17:00+01:00"));
	}

	@Test
	void missingDueIsNull() {
		assertThat(c.dueAtOf(new SuggestedAction("x", null))).isNull();
	}
}
