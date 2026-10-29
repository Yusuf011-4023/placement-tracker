package com.placementtracker.notify;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class NotificationLogTest {

	@TempDir
	static Path tmp;

	@DynamicPropertySource
	static void props(DynamicPropertyRegistry registry) {
		registry.add("tracker.data-dir", () -> tmp.toString());
	}

	@Autowired NotificationLog log;

	@Test
	void markIfNewIsTrueOnce() {
		assertThat(log.markIfNew("k1")).isTrue();
		assertThat(log.markIfNew("k1")).isFalse();
		assertThat(log.markIfNew("k2")).isTrue();
	}
}
