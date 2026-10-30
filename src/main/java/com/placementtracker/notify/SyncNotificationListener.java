package com.placementtracker.notify;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.placementtracker.application.ActionRepository;
import com.placementtracker.application.ApplicationAction;
import com.placementtracker.application.ApplicationRepository;
import com.placementtracker.sync.SyncCompletedEvent;

/** After each sync: one batched notification for new to-dos and one for new review items. */
@Component
public class SyncNotificationListener {

	private static final Logger log = LoggerFactory.getLogger(SyncNotificationListener.class);
	private static final int MAX_LISTED = 3;

	private final ActionRepository actions;
	private final ApplicationRepository applications;
	private final NotificationLog notified;
	private final Notifier notifier;

	public SyncNotificationListener(ActionRepository actions, ApplicationRepository applications,
			NotificationLog notified, Notifier notifier) {
		this.actions = actions;
		this.applications = applications;
		this.notified = notified;
		this.notifier = notifier;
	}

	@EventListener
	public void onSync(SyncCompletedEvent event) {
		try {
			notifyActions(event);
			int review = event.report().newReviewItems();
			if (review > 0) {
				notifier.notify("Please check", review + (review == 1 ? " email needs" : " emails need") + " checking");
			}
		}
		catch (RuntimeException e) {
			log.warn("Sync notification failed: {}", e.getMessage());
		}
	}

	private void notifyActions(SyncCompletedEvent event) {
		List<String> lines = new ArrayList<>();
		List<String> keys = new ArrayList<>();
		for (Long id : event.report().newActionIds()) {
			ApplicationAction a = actions.find(id).orElse(null);
			if (a == null || a.status() != ApplicationAction.Status.OPEN) {
				continue;
			}
			if (notified.seen("action:" + a.id() + ":new")) {
				continue;
			}
			String company = applications.find(a.applicationId()).map(x -> x.company()).orElse("Unknown");
			lines.add(company + ": " + a.description());
			keys.add("action:" + a.id() + ":new");
		}
		if (lines.isEmpty()) {
			return;
		}
		String body = String.join("; ", lines.subList(0, Math.min(MAX_LISTED, lines.size())));
		if (lines.size() > MAX_LISTED) {
			body += " +" + (lines.size() - MAX_LISTED) + " more";
		}
		if (notifier.notify("New to-dos", body)) {
			keys.forEach(notified::markIfNew);
		}
	}
}
