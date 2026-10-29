package com.placementtracker.notify;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.placementtracker.application.ActionRepository;
import com.placementtracker.application.ApplicationRepository;
import com.placementtracker.application.Stage;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
abstract class NotifyTestBase {

	static final Path tmp = newTmp();

	static Path newTmp() {
		try {
			Path p = java.nio.file.Files.createTempDirectory("notify-test");
			p.toFile().deleteOnExit();
			return p;
		}
		catch (java.io.IOException e) {
			throw new IllegalStateException(e);
		}
	}

	@DynamicPropertySource
	static void props(DynamicPropertyRegistry registry) {
		registry.add("tracker.data-dir", () -> tmp.toString());
	}

	@Autowired ActionRepository actions;
	@Autowired ApplicationRepository apps;
	@Autowired NotificationLog log;
	@Autowired JdbcTemplate jdbc;

	final FakeNotifier notifier = new FakeNotifier();

	@BeforeEach
	void clean() {
		for (String t : new String[] { "notifications_sent", "review_items", "field_locks", "actions",
				"stage_history", "emails", "applications" }) {
			jdbc.update("DELETE FROM " + t);
		}
		notifier.sent.clear();
	}

	long app(String company) {
		return apps.insert(company, company.toLowerCase(), null, Stage.APPLIED, LocalDate.of(2026, 10, 1), null);
	}

	long action(long appId, String description, OffsetDateTime due) {
		return actions.insert(appId, description, due, Stage.APPLIED, null);
	}

	static class FakeNotifier implements Notifier {
		final List<String[]> sent = new ArrayList<>();

		@Override
		public void notify(String title, String message) {
			sent.add(new String[] { title, message });
		}
	}
}
