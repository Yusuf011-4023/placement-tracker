package com.placementtracker.classify;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.placementtracker.application.Stage;

/** Structured result Claude returns for one email. */
public record Classification(
		@JsonPropertyDescription("YES if the email is about the owner's placement/internship application, NO if not, UNSURE if in doubt.") Relevance relevance,
		@JsonPropertyDescription("The hiring company (never the ATS vendor or job board). Null if unknown.") String company,
		@JsonPropertyDescription("The role title applied for. Null if unknown.") String role,
		@JsonPropertyDescription("The application stage this email implies. Null when relevance is NO.") Stage stage,
		@JsonPropertyDescription("Every action with a deadline or event time; empty if none.") List<SuggestedAction> actions,
		@JsonPropertyDescription("Confidence in this classification between 0.0 and 1.0.") double confidence,
		@JsonPropertyDescription("One short sentence explaining the decision.") String reason) {

	private static final ZoneId LONDON = ZoneId.of("Europe/London");

	/** Parses the action's due date; a date-only value means 23:59 London time. Null when absent. */
	public OffsetDateTime dueAtOf(SuggestedAction a) {
		if (a == null || a.dueAt() == null || a.dueAt().isBlank()) {
			return null;
		}
		String v = a.dueAt().trim();
		if (v.length() == 10) {
			return LocalDate.parse(v).atTime(LocalTime.of(23, 59)).atZone(LONDON).toOffsetDateTime();
		}
		return OffsetDateTime.parse(v);
	}
}
