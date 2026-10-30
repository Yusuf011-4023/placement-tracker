package com.placementtracker.mail.gmail;

import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Spaces Gmail API calls at least {@code 1/perSecond} apart (sleep-based, thread-safe). Idle time is not banked, so
 * a pause never allows a later burst.
 */
public final class RequestThrottle {

	/** Sleeps for the given number of nanoseconds; replaceable so tests need not really sleep. */
	@FunctionalInterface
	public interface Pause {
		void sleepNanos(long nanos);
	}

	private final long intervalNanos;
	private final LongSupplier clock;
	private final Pause pause;
	private long nextFree;
	private boolean started;

	public RequestThrottle(int perSecond, LongSupplier nanoClock, Pause pause) {
		this.intervalNanos = TimeUnit.SECONDS.toNanos(1) / Math.max(1, perSecond);
		this.clock = nanoClock;
		this.pause = pause;
	}

	public static RequestThrottle perSecond(int perSecond) {
		return new RequestThrottle(perSecond, System::nanoTime, nanos -> {
			try {
				TimeUnit.NANOSECONDS.sleep(nanos);
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		});
	}

	/** Blocks until a request may be sent. */
	public synchronized void acquire() {
		long now = clock.getAsLong();
		if (!started || nextFree < now) {
			nextFree = now;
			started = true;
		}
		long wait = nextFree - now;
		nextFree += intervalNanos;
		if (wait > 0) {
			pause.sleepNanos(wait);
		}
	}
}
