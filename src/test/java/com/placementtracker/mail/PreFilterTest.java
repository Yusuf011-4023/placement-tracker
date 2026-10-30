package com.placementtracker.mail;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.placementtracker.config.PreFilterProperties;

class PreFilterTest {

	private static final List<String> KEYWORDS = List.of("application", "applied", "apply", "placement",
			"internship", "intern", "assessment", "online test", "interview", "offer", "candidate", "hiring",
			"next steps", "unfortunately", "invitation", "online assessment", "role", "recruit*");

	private static final List<String> ATS_DOMAINS = List.of("myworkday.com", "greenhouse.io", "linkedin.com",
			"brightnetwork.co.uk");

	private static final List<String> BLOCKED = List.of("jobalerts-noreply@linkedin.com", "match.indeed.com");

	private static final PreFilter FILTER = new PreFilter(
			new PreFilterProperties(KEYWORDS, ATS_DOMAINS, List.of("example-careers.org"), BLOCKED), Path.of("none"));

	private static MailMessage msg(String from, String subject, String body, Set<String> labels) {
		return new MailMessage(MailAccount.GMAIL, "m1", "t1", null, from, subject,
				Instant.parse("2026-10-20T10:00:00Z"), body, null, labels);
	}

	@Test
	void keywordInSubjectPasses() {
		assertThat(FILTER.shouldClassify(msg("recruiting@northwind.com", "Your application to Northwind Bank",
				"Thanks for getting in touch.", Set.of()))).isTrue();
	}

	@Test
	void atsSenderPassesWithoutKeyword() {
		assertThat(FILTER.shouldClassify(msg("Northwind <noreply@myworkday.com>", "Update", "", Set.of())))
			.isTrue();
	}

	@Test
	void subdomainOfAtsSenderPasses() {
		assertThat(FILTER.shouldClassify(msg("jobs@mail.greenhouse.io", "Update", "", Set.of()))).isTrue();
	}

	@Test
	void lookalikeDomainDoesNotMatchAtsSender() {
		assertThat(FILTER.shouldClassify(msg("bot@fakemyworkday.com", "Update", "", Set.of()))).isFalse();
	}

	@Test
	void extraDomainPasses() {
		assertThat(FILTER.shouldClassify(msg("hr@example-careers.org", "Update", "", Set.of()))).isTrue();
	}

	@Test
	void keywordInBodyPasses() {
		assertThat(FILTER.shouldClassify(msg("hr@acme.com", "Hello",
				"Please complete the online assessment by Friday.", Set.of()))).isTrue();
	}

	@Test
	void spamWithKeywordStillPasses() {
		assertThat(FILTER.shouldClassify(msg("hr@acme.com", "Interview invitation", "", Set.of("SPAM"))))
			.isTrue();
	}

	@Test
	void socialWithoutKeywordFiltered() {
		assertThat(FILTER.shouldClassify(msg("notify@facebookmail.com", "X liked your post", "",
				Set.of("SOCIAL")))).isFalse();
	}

	@Test
	void promoWithoutKeywordFiltered() {
		assertThat(FILTER.shouldClassify(msg("deals@shoes.example", "50% off trainers", "",
				Set.of("PROMOTIONS")))).isFalse();
	}

	@Test
	void plainEmailWithoutSignalFiltered() {
		assertThat(FILTER.shouldClassify(msg("friend@gmail.com", "Lunch tomorrow?", "Shall we say noon?",
				Set.of()))).isFalse();
	}

	@Test
	void keywordsMatchCaseInsensitively() {
		assertThat(FILTER.shouldClassify(msg("hr@acme.com", "INTERVIEW SCHEDULED", "", Set.of()))).isTrue();
	}

	@Test
	void nullFieldsDoNotThrow() {
		assertThat(FILTER.shouldClassify(msg(null, null, null, null))).isFalse();
	}

	@Test
	void roleDoesNotMatchInsideControl() {
		assertThat(FILTER.shouldClassify(msg("a@unknown.example", "Take control of your energy bill", "", Set.of())))
			.isFalse();
	}

	@Test
	void recruitPrefixMatchesRecruiter() {
		assertThat(FILTER.shouldClassify(msg("a@unknown.example", "Hello", "A recruiter will be in touch",
				Set.of()))).isTrue();
	}

	@Test
	void internDoesNotMatchInternational() {
		assertThat(FILTER.shouldClassify(msg("a@unknown.example", "International shipping now free", "", Set.of())))
			.isFalse();
	}

	@Test
	void blockedAddressBeatsAtsDomain() {
		assertThat(FILTER.shouldClassify(msg("LinkedIn <jobalerts-noreply@linkedin.com>",
				"New internship roles for you", "", Set.of()))).isFalse();
	}

	@Test
	void jobsNoreplyLinkedinStillPasses() {
		assertThat(FILTER.shouldClassify(msg("jobs-noreply@linkedin.com", "Your application was sent to Capgemini",
				"", Set.of()))).isTrue();
	}

	@Test
	void blockedDomainCoversSubdomains() {
		assertThat(FILTER.shouldClassify(msg("donotreply@match.indeed.com", "Interview invitation", "", Set.of())))
			.isFalse();
		assertThat(FILTER.shouldClassify(msg("indeedapply@indeed.com", "Interview invitation", "", Set.of())))
			.isTrue();
	}

	@Test
	void blockedSendersFileIsLoaded(@TempDir Path dir) throws IOException {
		Path file = dir.resolve("blocked-senders.txt");
		Files.writeString(file, "# comment\n\n  Shop.Example.COM  \ndeals@other.example\n");
		PreFilter filter = new PreFilter(
				new PreFilterProperties(KEYWORDS, ATS_DOMAINS, List.of(), List.of()), file);

		assertThat(filter.shouldClassify(msg("x@news.shop.example.com", "Interview invitation", "", Set.of())))
			.isFalse();
		assertThat(filter.shouldClassify(msg("deals@other.example", "Interview invitation", "", Set.of())))
			.isFalse();
		assertThat(filter.shouldClassify(msg("jobs@other.example", "Interview invitation", "", Set.of())))
			.isTrue();
	}

	@Test
	void missingBlockedSendersFileIsFine(@TempDir Path dir) {
		PreFilter filter = new PreFilter(
				new PreFilterProperties(KEYWORDS, ATS_DOMAINS, List.of(), List.of()), dir.resolve("blocked-senders.txt"));

		assertThat(filter.shouldClassify(msg("a@x.example", "Interview invitation", "", Set.of()))).isTrue();
		assertThat(filter.shouldClassify(msg("a@x.example", "Lunch?", "", Set.of()))).isFalse();
	}

	@Test
	void unreadableBlockedSendersFileIsIgnored(@TempDir Path dir) {
		// A directory in place of the file cannot be read as text.
		PreFilter filter = new PreFilter(
				new PreFilterProperties(KEYWORDS, ATS_DOMAINS, List.of(), List.of()), dir);

		assertThat(filter.shouldClassify(msg("a@x.example", "Interview invitation", "", Set.of()))).isTrue();
	}
}
