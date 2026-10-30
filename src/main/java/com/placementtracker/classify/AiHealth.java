package com.placementtracker.classify;

import java.time.Clock;
import java.time.Instant;

import org.springframework.stereotype.Component;

/**
 * In-memory record of the classifier's latest error and latest success, so the dashboard can say when the AI is
 * unavailable rather than letting emails pile up silently. Reset on restart, which is fine: the next sync re-learns it.
 */
@Component
public class AiHealth {

	public record Snapshot(String lastError, Instant lastErrorAt, Instant lastSuccessAt) {
	}

	private final Clock clock;
	private String lastError;
	private Instant lastErrorAt;
	private Instant lastSuccessAt;

	public AiHealth(Clock clock) {
		this.clock = clock;
	}

	public synchronized void recordFailure(String error) {
		lastError = error;
		lastErrorAt = clock.instant();
	}

	public synchronized void recordSuccess() {
		lastSuccessAt = clock.instant();
	}

	public synchronized Snapshot snapshot() {
		return new Snapshot(lastError, lastErrorAt, lastSuccessAt);
	}
}
