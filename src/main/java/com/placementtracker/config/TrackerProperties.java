package com.placementtracker.config;

import java.nio.file.Path;
import java.time.Instant;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Application settings under the {@code tracker.*} prefix. */
@ConfigurationProperties("tracker")
public record TrackerProperties(Path dataDir, Sync sync, Ai ai, Forwarding forwarding) {

	public TrackerProperties {
		if (forwarding == null) {
			forwarding = new Forwarding(null);
		}
	}

	public record Sync(int intervalMinutes, Instant backfillFrom) {
	}

	public record Ai(String model, double confidenceThreshold, int maxAttempts) {
	}

	/** {@code fromAddress} may be null when forwarding is not configured. */
	public record Forwarding(String fromAddress) {
	}
}
