package com.placementtracker.mail;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** Checks that the {@code tracker.prefilter.*} lists in application.yml bind to the pre-filter. */
@SpringBootTest
class PreFilterBindingTest {

	@Autowired
	private PreFilter preFilter;

	@Test
	void configuredAtsDomainBindsFromYaml() {
		MailMessage m = new MailMessage(MailAccount.GMAIL, "m1", "t1", null, "noreply@myworkday.com", "Update",
				Instant.parse("2026-10-20T10:00:00Z"), "", null, Set.of());

		assertThat(preFilter.shouldClassify(m)).isTrue();
	}
}
