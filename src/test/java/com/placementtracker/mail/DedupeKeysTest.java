package com.placementtracker.mail;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Set;

import org.junit.jupiter.api.Test;

class DedupeKeysTest {

	private static MailMessage msg(String internetMessageId, String from, String subject, String receivedAt) {
		return new MailMessage(MailAccount.OUTLOOK, "m-" + subject, "t1", internetMessageId, from, subject,
				Instant.parse(receivedAt), "body", null, Set.of());
	}

	@Test
	void sameInternetMessageIdSameKey() {
		MailMessage a = msg("<abc@mail.acme.com>", "hr@acme.com", "Interview invite", "2026-10-20T10:00:00Z");
		MailMessage b = msg("<abc@mail.acme.com>", "other@acme.com", "Something else", "2026-10-25T09:00:00Z");

		assertThat(DedupeKeys.of(a)).isEqualTo(DedupeKeys.of(b));
	}

	@Test
	void forwardedPrefixIgnored() {
		MailMessage original = msg(null, "Recruiter <hr@acme.com>", "Interview invite", "2026-10-20T10:00:00Z");
		MailMessage forwarded = msg(null, "hr@acme.com", "Fwd: Interview invite", "2026-10-20T10:03:00Z");

		assertThat(DedupeKeys.of(forwarded)).isEqualTo(DedupeKeys.of(original));
	}

	@Test
	void differentSenderGivesDifferentKey() {
		MailMessage a = msg(null, "hr@acme.com", "Interview invite", "2026-10-20T10:00:00Z");
		MailMessage b = msg(null, "hr@other.com", "Interview invite", "2026-10-20T10:00:00Z");

		assertThat(DedupeKeys.of(a)).isNotEqualTo(DedupeKeys.of(b));
	}

	@Test
	void tabsAndNewlinesNormalisedLikeSpaces() {
		MailMessage spaced = msg(null, "hr@acme.com", "Interview  invite", "2026-10-20T10:00:00Z");
		MailMessage tabbed = msg(null, "hr@acme.com", "Interview\tinvite", "2026-10-20T10:01:00Z");
		MailMessage folded = msg(null, "hr@acme.com", "Re:\tInterview\r\n invite", "2026-10-20T10:02:00Z");

		assertThat(DedupeKeys.of(tabbed)).isEqualTo(DedupeKeys.of(spaced));
		assertThat(DedupeKeys.of(folded)).isEqualTo(DedupeKeys.of(spaced));
	}
}
