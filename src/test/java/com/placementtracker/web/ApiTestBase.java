package com.placementtracker.web;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.placementtracker.application.ActionRepository;
import com.placementtracker.application.ApplicationRepository;
import com.placementtracker.application.HistoryRepository;
import com.placementtracker.application.Stage;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Import(ApiTestBase.FixedClockConfig.class)
abstract class ApiTestBase {

	static final Instant NOW = Instant.parse("2026-10-29T12:00:00Z");

	// One directory per JVM: the Spring context is cached and shared by every subclass.
	static final Path tmp = newTempDir();

	private static Path newTempDir() {
		try {
			return java.nio.file.Files.createTempDirectory("tracker-api-test");
		}
		catch (java.io.IOException e) {
			throw new IllegalStateException(e);
		}
	}

	@DynamicPropertySource
	static void props(DynamicPropertyRegistry registry) {
		registry.add("tracker.data-dir", () -> tmp.toString());
		registry.add("tracker.sync.scheduler-enabled", () -> "false");
	}

	@TestConfiguration
	static class FixedClockConfig {
		@Bean
		@Primary
		Clock fixedClock() {
			return Clock.fixed(NOW, ZoneId.of("UTC"));
		}
	}

	@Autowired MockMvc mvc;
	@Autowired JdbcTemplate jdbc;
	@Autowired ApplicationRepository apps;
	@Autowired ActionRepository actions;
	@Autowired HistoryRepository history;

	@BeforeEach
	void cleanDb() {
		for (String t : new String[] { "review_items", "field_locks", "actions", "stage_history", "emails",
				"applications", "sync_state" }) {
			jdbc.update("DELETE FROM " + t);
		}
	}

	long app(String company, Stage stage) {
		return apps.insert(company, company.toLowerCase(), "Engineer", stage, null, null);
	}

	long action(long appId, String description, long hoursFromNow, Stage stage) {
		return actions.insert(appId, description, NOW.plusSeconds(hoursFromNow * 3600).atOffset(ZoneOffset.UTC), stage,
				null);
	}

	/** Makes a request look like it comes from the owner browser page. */
	static MockHttpServletRequestBuilder own(MockHttpServletRequestBuilder b) {
		return b.header("Host", "localhost:8765").header("X-Tracker", "1").contentType(MediaType.APPLICATION_JSON);
	}
}
