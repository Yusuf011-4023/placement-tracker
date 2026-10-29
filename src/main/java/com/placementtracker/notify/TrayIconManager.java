package com.placementtracker.notify;

import java.awt.Color;
import java.awt.Desktop;
import java.awt.GraphicsEnvironment;
import java.awt.Graphics2D;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.RenderingHints;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.image.BufferedImage;
import java.net.URI;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.placementtracker.web.SyncRunner;

/** Adds the tray icon with Open tracker / Check now / Quit. Disable with {@code tracker.tray.enabled=false}. */
@Component
@ConditionalOnProperty(name = "tracker.tray.enabled", havingValue = "true", matchIfMissing = true)
public class TrayIconManager {

	private static final Logger log = LoggerFactory.getLogger(TrayIconManager.class);
	static final String URL = "http://127.0.0.1:8765";

	private final Notifier notifier;
	private final SyncRunner runner;
	private final ApplicationContext context;

	public TrayIconManager(Notifier notifier, SyncRunner runner, ApplicationContext context) {
		this.notifier = notifier;
		this.runner = runner;
		this.context = context;
	}

	@EventListener(ApplicationReadyEvent.class)
	@org.springframework.core.annotation.Order(org.springframework.core.Ordered.HIGHEST_PRECEDENCE)
	public void install() {
		if (GraphicsEnvironment.isHeadless() || !SystemTray.isSupported()) {
			log.warn("System tray not available; continuing without a tray icon");
			return;
		}
		try {
			PopupMenu menu = new PopupMenu();
			MenuItem open = new MenuItem("Open tracker");
			open.addActionListener(e -> openTracker());
			MenuItem check = new MenuItem("Check now");
			check.addActionListener(e -> runner.trigger());
			MenuItem quit = new MenuItem("Quit");
			quit.addActionListener(e -> System.exit(SpringApplication.exit(context)));
			menu.add(open);
			menu.add(check);
			menu.addSeparator();
			menu.add(quit);

			TrayIcon icon = new TrayIcon(drawIcon(), "Placement Tracker", menu);
			icon.setImageAutoSize(true);
			icon.addActionListener(e -> openTracker());
			SystemTray.getSystemTray().add(icon);
			if (notifier instanceof TrayNotifier tn) {
				tn.attach(icon);
			}
		}
		catch (Exception | java.awt.AWTError e) {
			log.warn("Could not add tray icon: {}", e.getMessage());
		}
	}

	static BufferedImage drawIcon() {
		BufferedImage img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = img.createGraphics();
		try {
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g.setColor(new Color(0x2563EB));
			g.fillOval(0, 0, 16, 16);
			g.setColor(Color.WHITE);
			g.fillOval(5, 5, 6, 6);
		}
		finally {
			g.dispose();
		}
		return img;
	}

	private void openTracker() {
		try {
			if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
				Desktop.getDesktop().browse(URI.create(URL));
			}
		}
		catch (Exception e) {
			log.warn("Could not open the tracker: {}", e.getMessage());
		}
	}
}
