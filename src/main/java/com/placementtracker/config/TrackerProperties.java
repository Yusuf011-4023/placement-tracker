package com.placementtracker.config;

import java.nio.file.Path;
import java.time.Instant;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Application settings under the {@code tracker.*} prefix. */
@ConfigurationProperties("tracker")
public record TrackerProperties(Path dataDir, Sync sync, Ai ai) {

	public record Sync(int intervalMinutes, Instant backfillFrom) {
	}

	public record Ai(String model, double confidenceThreshold, int maxAttempts) {
	}
}
