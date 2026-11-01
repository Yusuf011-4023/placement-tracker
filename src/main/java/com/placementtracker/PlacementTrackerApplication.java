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
		if (LaunchHelper.trackerAlreadyRunning(LaunchHelper.DASHBOARD, Duration.ofSeconds(1))) {
			// Already running: just show the page (a second autostart does nothing) and leave without starting Spring.
			if (!LaunchHelper.isAutostart(args)) {
				BrowserOpener.browse(LaunchHelper.HOME);
			}
			return;
		}
		new SpringApplicationBuilder(PlacementTrackerApplication.class).headless(false).run(args);
	}
}
