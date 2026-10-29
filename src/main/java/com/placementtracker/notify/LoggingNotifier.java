package com.placementtracker.notify;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Fallback when there is no system tray (headless or unsupported). */
public class LoggingNotifier implements Notifier {

	private static final Logger log = LoggerFactory.getLogger(LoggingNotifier.class);

	@Override
	public void notify(String title, String message) {
		log.info("Notification: {} - {}", title, message);
	}
}
