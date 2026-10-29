package com.placementtracker.notify;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.placementtracker.application.ActionRepository;
import com.placementtracker.application.ApplicationAction;
import com.placementtracker.application.ApplicationRepository;

/** Reminds 3 days and 1 day before an open action is due. */
@Component
@ConditionalOnProperty(name = "tracker.reminders.enabled", havingValue = "true", matchIfMissing = true)
public class ReminderService {

	private static final Logger log = LoggerFactory.getLogger(ReminderService.class);
	private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("EEE d MMM HH:mm", Locale.UK)
			.withZone(ZoneId.of("Europe/London"));

	private final ActionRepository actions;
	private final ApplicationRepository applications;
	private final NotificationLog notified;
	private final Notifier notifier;
	private final Clock clock;

	public ReminderService(ActionRepository actions, ApplicationRepository applications, NotificationLog notified,
			Notifier notifier, Clock clock) {
		this.actions = actions;
		this.applications = applications;
		this.notified = notified;
		this.notifier = notifier;
		this.clock = clock;
	}

	@EventListener(ApplicationReadyEvent.class)
	void onReady() {
		run();
	}

	@Scheduled(fixedDelay = 15, initialDelay = 15, timeUnit = TimeUnit.MINUTES)
	public void run() {
		try {
			OffsetDateTime now = OffsetDateTime.now(clock);
			for (ApplicationAction a : actions.findOpen()) {
				if (a.dueAt() != null) {
					remind(a, Duration.between(now, a.dueAt()));
				}
			}
		}
		catch (RuntimeException e) {
			log.warn("Reminder check failed: {}", e.getMessage());
		}
	}

	private void remind(ApplicationAction a, Duration left) {
		if (left.isNegative() || left.isZero() || left.compareTo(Duration.ofHours(72)) > 0) {
			return;
		}
		boolean oneDay = left.compareTo(Duration.ofHours(24)) <= 0;
		String key3 = "action:" + a.id() + ":3d";
		String key1 = "action:" + a.id() + ":1d";
		String title;
		if (oneDay) {
			boolean first = notified.markIfNew(key1);
			notified.markIfNew(key3);
			if (!first) {
				return;
			}
			title = "Due tomorrow";
		}
		else {
			if (!notified.markIfNew(key3)) {
				return;
			}
			title = "Due in 3 days";
		}
		String company = applications.find(a.applicationId()).map(x -> x.company()).orElse("Unknown");
		notifier.notify(title, company + ": " + a.description() + " — due " + WHEN.format(a.dueAt()));
	}
}
