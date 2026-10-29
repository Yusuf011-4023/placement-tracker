package com.placementtracker.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

/** Checks the shipped application.yml settings that tests otherwise override or never exercise. */
class ApplicationYmlTest {

	private static StandardEnvironment environment(Map<String, Object> overrides) throws Exception {
		StandardEnvironment env = new StandardEnvironment();
		new YamlPropertySourceLoader().load("application.yml", new ClassPathResource("application.yml"))
				.forEach(env.getPropertySources()::addLast);
		env.getPropertySources().addFirst(new MapPropertySource("overrides", overrides));
		return env;
	}

	@Test
	void logFileLivesInTheDataDir() throws Exception {
		StandardEnvironment env = environment(Map.of("tracker.data-dir", "D:/tracker-data"));

		assertThat(env.getProperty("logging.file.name")).isEqualTo("D:/tracker-data/logs/tracker.log");
		assertThat(env.getProperty("logging.logback.rollingpolicy.max-file-size")).isNotBlank();
		assertThat(env.getProperty("logging.logback.rollingpolicy.max-history")).isNotBlank();
	}

	@Test
	void logFileDefaultsToTheDefaultDataDir() throws Exception {
		StandardEnvironment env = environment(Map.of());

		assertThat(Path.of(env.getProperty("logging.file.name")))
				.isEqualTo(DataDir.defaultRoot().resolve("logs").resolve("tracker.log"));
	}

	@Test
	void schedulerHasTwoThreads() throws Exception {
		assertThat(environment(Map.of()).getProperty("spring.task.scheduling.pool.size")).isEqualTo("2");
	}
}
