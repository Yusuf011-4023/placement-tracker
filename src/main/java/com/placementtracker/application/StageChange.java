package com.placementtracker.application;

import java.time.Instant;

public record StageChange(long id, long applicationId, Stage fromStage, Stage toStage, Instant changedAt,
		String source, Long emailId) {
}
