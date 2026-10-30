package com.placementtracker;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class PlacementTrackerApplication {

	public static void main(String[] args) {
		new SpringApplicationBuilder(PlacementTrackerApplication.class).headless(false).run(args);
	}
}
