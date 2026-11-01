package com.placementtracker.config;

import java.nio.file.Path;
import java.time.Instant;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Application settings under the {@code tracker.*} prefix. */
@ConfigurationProperties("tracker")
public record TrackerProperties(Path dataDir, Sync sync, Ai ai, Gmail gmail) {

	public record Sync(int intervalMinutes, Instant backfillFrom) {
	}

	/** Gmail API limits; a missing or non-positive value falls back to the default. */
	public record Gmail(int maxRequestsPerSecond, int maxMessagesPerRun, int fetchThreads) {

		public static final int DEFAULT_REQUESTS_PER_SECOND = 10;
		public static final int DEFAULT_MESSAGES_PER_RUN = 500;
		public static final int DEFAULT_FETCH_THREADS = 4;

		public int fetchThreadCount() {
			return fetchThreads > 0 ? fetchThreads : DEFAULT_FETCH_THREADS;
		}

		public int requestsPerSecond() {
			return maxRequestsPerSecond > 0 ? maxRequestsPerSecond : DEFAULT_REQUESTS_PER_SECOND;
		}

		public int messagesPerRun() {
			return maxMessagesPerRun > 0 ? maxMessagesPerRun : DEFAULT_MESSAGES_PER_RUN;
		}
	}

	public record Ai(String model, double confidenceThreshold, int maxAttempts) {
	}
}
