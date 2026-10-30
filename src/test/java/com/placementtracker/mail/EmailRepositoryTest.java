package com.placementtracker.mail;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class EmailRepositoryTest {

	@TempDir
	static Path tmp;

	@DynamicPropertySource
	static void props(DynamicPropertyRegistry registry) {
		registry.add("tracker.data-dir", () -> tmp.toString());
	}

	@Autowired EmailRepository emails;
	@Autowired JdbcTemplate jdbc;

	@BeforeEach
	void clean() {
		jdbc.update("DELETE FROM emails");
	}

	private MailMessage msg(String id) {
		return new MailMessage(MailAccount.GMAIL, id, "t-" + id, "<" + id + "@x>", "hr@acme.com", "Hello",
				Instant.parse("2026-10-01T09:00:00Z"), "body", "http://link/" + id, Set.of());
	}

	@Test
	void insertIfNewIgnoresSameMessageId() {
		var first = emails.insertIfNew(msg("m1"), "key1");
		var second = emails.insertIfNew(msg("m1"), "key1");

		assertThat(first).isPresent();
		EmailRecord r = first.get();
		assertThat(r.status()).isEqualTo(EmailStatus.PENDING);
		assertThat(r.dedupeKey()).isEqualTo("key1");
		assertThat(r.receivedAt()).isEqualTo(Instant.parse("2026-10-01T09:00:00Z"));
		assertThat(r.from()).isEqualTo("hr@acme.com");
		assertThat(second).isEmpty();
	}

	@Test
	void retryableExcludesMaxedAttempts() {
		long a = emails.insertIfNew(msg("a"), "ka").orElseThrow().id();
		long b = emails.insertIfNew(msg("b"), "kb").orElseThrow().id();
		emails.setStatus(a, EmailStatus.FAILED);
		emails.setStatus(b, EmailStatus.FAILED);
		emails.recordFailure(a, "boom");
		emails.recordFailure(b, "boom");
		emails.recordFailure(b, "boom");
		assertThat(emails.recordFailure(b, "boom")).isEqualTo(3);

		assertThat(emails.findRetryable(3)).extracting(EmailRecord::id).containsExactly(a);
	}

	@Test
	void dedupeAndLinking() {
		long a = emails.insertIfNew(msg("a"), "same").orElseThrow().id();
		long b = emails.insertIfNew(msg("b"), "same").orElseThrow().id();
		assertThat(emails.existsDedupeKey("same", a)).isTrue();
		assertThat(emails.existsDedupeKey("other", a)).isFalse();
		emails.linkApplication(b, 42L);
		assertThat(emails.find(b).orElseThrow().applicationId()).isEqualTo(42L);
		assertThat(emails.find(a).orElseThrow().applicationId()).isNull();
	}
}
