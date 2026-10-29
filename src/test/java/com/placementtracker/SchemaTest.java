package com.placementtracker;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class SchemaTest {

	@TempDir
	static Path tmp;

	@DynamicPropertySource
	static void props(DynamicPropertyRegistry registry) {
		registry.add("tracker.data-dir", () -> tmp.toString());
	}

	@Autowired
	JdbcTemplate jdbc;

	@Test
	void migrationCreatesAllTables() {
		List<String> tables = jdbc.queryForList("SELECT name FROM sqlite_master WHERE type='table'", String.class);

		assertThat(tables).contains("applications", "field_locks", "actions", "emails", "stage_history",
				"review_items", "sync_state", "notifications_sent");
		assertThat(Files.exists(tmp.resolve("tracker.db"))).isTrue();
		assertThat(jdbc.queryForList("SELECT version FROM flyway_schema_history WHERE success = 1", String.class))
				.contains("1", "2");
	}
}
