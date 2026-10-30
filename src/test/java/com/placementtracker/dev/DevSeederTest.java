package com.placementtracker.dev;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.placementtracker.application.ApplicationRepository;
import com.placementtracker.application.Stage;
import com.placementtracker.review.ReviewRepository;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class DevSeederTest {

	static final Path tmp = newTempDir();

	static Path newTempDir() {
		try {
			return Files.createTempDirectory("tracker-seed-test");
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

	@Autowired DevSeeder seeder;
	@Autowired ApplicationRepository apps;
	@Autowired ReviewRepository reviews;
	@Autowired JdbcTemplate jdbc;

	@Test
	void seedsAnEmptyDatabaseOnceAndLeavesAFilledOneAlone() {
		for (String t : new String[] { "review_items", "field_locks", "actions", "stage_history", "emails",
				"applications" }) {
			jdbc.update("DELETE FROM " + t);
		}

		assertThat(seeder.seedIfEmpty()).isTrue();
		assertThat(apps.findAll()).hasSize(10)
				.anyMatch(a -> a.company().equals("Northwind Bank") && a.stage() == Stage.ONLINE_TEST)
				.anyMatch(a -> a.stage() == Stage.NOT_SUBMITTED);
		assertThat(reviews.findOpen()).hasSize(1);

		// Second call: the database is no longer empty, so nothing is added.
		assertThat(seeder.seedIfEmpty()).isFalse();
		assertThat(apps.findAll()).hasSize(10);
		assertThat(reviews.findOpen()).hasSize(1);
	}
}
