package com.placementtracker.application;

import java.time.OffsetDateTime;

public record ApplicationAction(long id, long applicationId, String description, OffsetDateTime dueAt,
		Status status, Stage stage) {

	public enum Status { OPEN, DONE, CLOSED }
}
