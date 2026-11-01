package com.placementtracker;

import com.placementtracker.launch.BrowserOpener;
import com.placementtracker.launch.LaunchHelper;
import java.time.Duration;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class PlacementTrackerApplication {

	public static void main(String[] args) {
		boolean probe = LaunchHelper.shouldProbe(args);
		if (probe && LaunchHelper.trackerAlreadyRunning(LaunchHelper.DASHBOARD, Duration.ofSeconds(1))) {
			openExistingAndExit(args);
		}
		try {
			new SpringApplicationBuilder(PlacementTrackerApplication.class).headless(false).run(args);
		}
		catch (RuntimeException e) {
			// Clicked while an auto-started copy was still binding the port: wait for it, then show its page.
			if (probe && LaunchHelper.isPortInUse(e) && waitForRunning()) {
				openExistingAndExit(args);
			}
			throw e;
		}
	}

	private static boolean waitForRunning() {
		for (int i = 0; i < 15; i++) {
			if (LaunchHelper.trackerAlreadyRunning(LaunchHelper.DASHBOARD, Duration.ofSeconds(1))) {
				return true;
			}
			try {
				Thread.sleep(1000);
			}
			catch (InterruptedException ie) {
				Thread.currentThread().interrupt();
				return false;
			}
		}
		return false;
	}

	private static void openExistingAndExit(String[] args) {
		// Just show the page (a second autostart does nothing) and leave without starting Spring.
		if (!LaunchHelper.isAutostart(args)) {
			BrowserOpener.browse(LaunchHelper.HOME);
		}
		System.out.println("Placement Tracker is already running; opened it in your browser.");
		System.exit(0);
	}
}
