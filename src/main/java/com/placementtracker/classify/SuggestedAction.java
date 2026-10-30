package com.placementtracker.classify;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

public record SuggestedAction(
		@JsonPropertyDescription("One short sentence saying what the applicant must do, e.g. 'Complete the online test'.") String description,
		@JsonPropertyDescription("Deadline or event time as ISO-8601: a date (2026-11-07) or a date-time with UTC offset (2026-10-30T17:00+01:00). Null if no date is given.") String dueAt) {
}
