package com.placementtracker.sync;

import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Runs the sync on a fixed delay. Disable with {@code tracker.sync.scheduler-enabled=false}. */
@Component
@ConditionalOnProperty(name = "tracker.sync.scheduler-enabled", havingValue = "true", matchIfMissing = true)
public class SyncScheduler {

	private static final Logger log = LoggerFactory.getLogger(SyncScheduler.class);

	private final SyncService sync;
	private final ApplicationEventPublisher events;

	public SyncScheduler(SyncService sync, ApplicationEventPublisher events) {
		this.sync = sync;
		this.events = events;
	}

	@Scheduled(fixedDelayString = "${tracker.sync.interval-minutes}",
			initialDelayString = "${tracker.sync.initial-delay-minutes:1}", timeUnit = TimeUnit.MINUTES)
	public void run() {
		try {
			events.publishEvent(new SyncCompletedEvent(sync.syncAll()));
		}
		catch (RuntimeException e) {
			log.error("Scheduled sync failed", e);
		}
	}
}
