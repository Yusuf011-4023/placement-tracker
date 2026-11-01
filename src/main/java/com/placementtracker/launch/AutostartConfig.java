package com.placementtracker.launch;

import java.util.Optional;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The registry is only wired up on Windows and only in the installed app (jpackage sets {@code jpackage.app-path}).
 * Anywhere else, including every test and a plain {@code java -jar} run, a no-op key is used.
 */
@Configuration
public class AutostartConfig {

	@Bean
	RunKey runKey() {
		boolean windows = System.getProperty("os.name", "").toLowerCase().startsWith("windows");
		if (windows && AutostartService.installedExe().isPresent()) {
			return new WindowsRunKey();
		}
		return new RunKey() {
			@Override
			public Optional<String> read(String name) {
				return Optional.empty();
			}

			@Override
			public void write(String name, String command) {
			}

			@Override
			public void delete(String name) {
			}
		};
	}
}
