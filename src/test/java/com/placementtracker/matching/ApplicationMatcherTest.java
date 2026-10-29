package com.placementtracker.matching;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.placementtracker.application.ApplicationRepository;
import com.placementtracker.application.Stage;
import com.placementtracker.classify.Classification;
import com.placementtracker.classify.Relevance;
import com.placementtracker.mail.MailAccount;
import com.placementtracker.mail.MailMessage;
import com.placementtracker.matching.MatchResult.Ambiguous;
import com.placementtracker.matching.MatchResult.Existing;
import com.placementtracker.matching.MatchResult.New;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ApplicationMatcherTest {

	@TempDir
	static Path tmp;

	@DynamicPropertySource
	static void props(DynamicPropertyRegistry registry) {
		registry.add("tracker.data-dir", () -> tmp.toString());
	}

	@Autowired ApplicationRepository apps;
	@Autowired ApplicationMatcher matcher;
	@Autowired JdbcTemplate jdbc;

	@BeforeEach
	void clean() {
		for (String t : new String[] { "field_locks", "actions", "emails", "stage_history", "applications" }) {
			jdbc.update("DELETE FROM " + t);
		}
	}

	@Test
	void threadAlreadyLinkedWins() {
		long linked = apps.insert("Acme", "acme", "Analyst", Stage.APPLIED, null, null);
		apps.insert("Acme", "acme", "Developer", Stage.APPLIED, null, null);
		jdbc.update("INSERT INTO emails (account, message_id, thread_id, application_id, status, attempts) "
				+ "VALUES ('GMAIL','m0','t1',?, 'PROCESSED', 0)", linked);

		MatchResult r = matcher.match(cls("Completely Different Co", "Tester", Stage.INTERVIEW), mail("t1"));

		assertThat(r).isEqualTo(new Existing(linked));
	}

	@Test
	void noApplicationForCompanyIsNew() {
		apps.insert("Other Co", "other co", "Analyst", Stage.APPLIED, null, null);

		MatchResult r = matcher.match(cls("Acme Ltd", "Analyst", Stage.APPLIED), mail("t9"));

		assertThat(r).isInstanceOf(New.class);
	}

	@Test
	void singleOpenSameCompanyMatchesWhenRoleBlankOrSimilar() {
		long id = apps.insert("Acme", "acme", "Technology Placement", Stage.APPLIED, null, null);

		assertThat(matcher.match(cls("Acme Ltd", "Technology Placement 2027", Stage.ONLINE_TEST), mail("t1")))
				.isEqualTo(new Existing(id));
		assertThat(matcher.match(cls("Acme", null, Stage.ONLINE_TEST), mail("t2")))
				.isEqualTo(new Existing(id));
	}

	@Test
	void blankRoleOnApplicationSideIsSimilar() {
		long id = apps.insert("Acme", "acme", null, Stage.APPLIED, null, null);

		assertThat(matcher.match(cls("Acme", "Data Analyst", Stage.ONLINE_TEST), mail("t1")))
				.isEqualTo(new Existing(id));
	}

	@Test
	void stopwordOnlyRoleCountsAsBlank() {
		long id = apps.insert("Acme", "acme", "Technology Placement", Stage.APPLIED, null, null);

		assertThat(matcher.match(cls("Acme", "Placement", Stage.ONLINE_TEST), mail("t1")))
				.isEqualTo(new Existing(id));
		assertThat(matcher.match(cls("Acme", "Internship 2027", Stage.ONLINE_TEST), mail("t2")))
				.isEqualTo(new Existing(id));
	}

	@Test
	void twoOpenSameCompanyDifferentRolesIsAmbiguous() {
		long a = apps.insert("Acme", "acme", "Data Placement", Stage.APPLIED, null, null);
		long b = apps.insert("Acme", "acme", "HR Placement", Stage.APPLIED, null, null);

		MatchResult r = matcher.match(cls("Acme", "Finance Placement", Stage.APPLIED), mail("t1"));

		assertThat(r).isEqualTo(new Ambiguous(List.of(a, b)));
	}

	@Test
	void twoOpenSameCompanyOneSimilarRoleIsExisting() {
		long a = apps.insert("Acme", "acme", "Data Engineer", Stage.APPLIED, null, null);
		apps.insert("Acme", "acme", "HR Partner", Stage.APPLIED, null, null);

		assertThat(matcher.match(cls("Acme", "Data Engineer", Stage.ONLINE_TEST), mail("t1")))
				.isEqualTo(new Existing(a));
	}

	@Test
	void singleOpenDifferentRoleIsAmbiguous() {
		long id = apps.insert("Acme", "acme", "Data Placement", Stage.APPLIED, null, null);

		MatchResult r = matcher.match(cls("Acme", "HR Placement", Stage.APPLIED), mail("t1"));

		assertThat(r).isEqualTo(new Ambiguous(List.of(id)));
	}

	@Test
	void closedOnlyApplicationsAreConsideredButAmbiguous() {
		long id = apps.insert("Acme", "acme", "Data Placement", Stage.REJECTED, null, null);

		MatchResult r = matcher.match(cls("Acme", "Data Placement", Stage.APPLIED), mail("t1"));

		assertThat(r).isEqualTo(new Ambiguous(List.of(id)));
	}

	@Test
	void missingCompanyIsAmbiguous() {
		apps.insert("Acme", "acme", "Data Placement", Stage.APPLIED, null, null);

		assertThat(matcher.match(cls(null, "Data Placement", Stage.APPLIED), mail("t1")))
				.isEqualTo(new Ambiguous(List.of()));
		assertThat(matcher.match(cls("  ", "Data Placement", Stage.APPLIED), mail("t2")))
				.isEqualTo(new Ambiguous(List.of()));
	}

	private static Classification cls(String company, String role, Stage stage) {
		return new Classification(Relevance.YES, company, role, stage, List.of(), 0.9, "test");
	}

	private static MailMessage mail(String threadId) {
		return new MailMessage(MailAccount.GMAIL, "m-" + threadId, threadId, null, null, null, Instant.now(),
				null, null, Set.of());
	}
}
