package com.placementtracker.notify;

import java.awt.TrayIcon;
import java.awt.TrayIcon.MessageType;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Shows Windows balloon notifications through the tray icon, which {@link TrayIconManager} installs. */
public class TrayNotifier implements Notifier {

	private static final Logger log = LoggerFactory.getLogger(TrayNotifier.class);

	private volatile TrayIcon icon;

	void attach(TrayIcon icon) {
		this.icon = icon;
	}

	@Override
	public void notify(String title, String message) {
		TrayIcon i = icon;
		if (i == null) {
			log.info("Notification (no tray icon yet): {} - {}", title, message);
			return;
		}
		try {
			i.displayMessage(title, message, MessageType.INFO);
		}
		catch (RuntimeException e) {
			log.warn("Could not show notification: {}", e.getMessage());
		}
	}
}
