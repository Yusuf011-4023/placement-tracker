package com.placementtracker.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ApplicationRepositoryTest {

	@TempDir
	static Path tmp;

	@DynamicPropertySource
	static void props(DynamicPropertyRegistry registry) {
		registry.add("tracker.data-dir", () -> tmp.toString());
	}

	@Autowired ApplicationRepository apps;
	@Autowired ActionRepository actions;
	@Autowired HistoryRepository history;
	@Autowired JdbcTemplate jdbc;

	@BeforeEach
	void clean() {
		for (String t : new String[] { "field_locks", "actions", "emails", "stage_history", "applications" }) {
			jdbc.update("DELETE FROM " + t);
		}
	}

	@Test
	void insertAndFindRoundTrips() {
		long id = apps.insert("Acme Ltd", "acme ltd", "Analyst", Stage.APPLIED, LocalDate.of(2026, 10, 1), "note");

		Application a = apps.find(id).orElseThrow();

		assertThat(a.company()).isEqualTo("Acme Ltd");
		assertThat(a.companyKey()).isEqualTo("acme ltd");
		assertThat(a.role()).isEqualTo("Analyst");
		assertThat(a.stage()).isEqualTo(Stage.APPLIED);
		assertThat(a.interviewRounds()).isZero();
		assertThat(a.appliedOn()).isEqualTo(LocalDate.of(2026, 10, 1));
		assertThat(a.notes()).isEqualTo("note");
		assertThat(a.lockedFields()).isEmpty();
		assertThat(apps.findByCompanyKey("acme ltd")).hasSize(1);
		assertThat(apps.findAll()).hasSize(1);
	}

	@Test
	void lockedFieldsAreLoaded() {
		long id = apps.insert("Acme", "acme", null, Stage.APPLIED, null, null);
		apps.lock(id, "stage");
		assertThat(apps.find(id).orElseThrow().lockedFields()).contains("stage");
	}

	@Test
	void updateCompanyRecomputesKey() {
		long id = apps.insert("Acme", "acme", null, Stage.APPLIED, null, null);
		apps.updateFields(id, Map.of("company", "  Globex ", "role", "Dev"));
		Application a = apps.find(id).orElseThrow();
		assertThat(a.company()).isEqualTo("  Globex ");
		assertThat(a.companyKey()).isEqualTo("globex");
		assertThat(a.role()).isEqualTo("Dev");
	}

	@Test
	void stageAndRoundsUpdate() {
		long id = apps.insert("Acme", "acme", null, Stage.APPLIED, null, null);
		apps.updateStage(id, Stage.INTERVIEW);
		apps.incrementInterviewRounds(id);
		Application a = apps.find(id).orElseThrow();
		assertThat(a.stage()).isEqualTo(Stage.INTERVIEW);
		assertThat(a.interviewRounds()).isEqualTo(1);
	}

	@Test
	void mergeMovesEmailsActionsHistory() {
		long target = apps.insert("Acme", "acme", null, Stage.APPLIED, null, null);
		long source = apps.insert("Acme2", "acme2", null, Stage.APPLIED, null, null);
		jdbc.update("INSERT INTO emails (account, message_id, thread_id, application_id, status, attempts) "
				+ "VALUES ('GMAIL','m1','t1',?, 'PROCESSED', 0)", source);
		long aid = actions.insert(source, "Do test", OffsetDateTime.parse("2026-11-01T10:00:00+00:00"),
				Stage.ONLINE_TEST, null);
		history.record(source, Stage.APPLIED, Stage.ONLINE_TEST, "EMAIL", null);
		assertThat(apps.findByThreadId("GMAIL", "t1")).contains(source);

		apps.mergeInto(target, source);

		assertThat(apps.find(source)).isEmpty();
		assertThat(apps.findByThreadId("GMAIL", "t1")).contains(target);
		assertThat(actions.findForApplication(target)).extracting(ApplicationAction::id).containsExactly(aid);
		assertThat(history.forApplication(target)).hasSize(1);
	}

	@Test
	void mergeIntoClosedTargetClosesMovedActionsAndCarriesLocks() {
		long target = apps.insert("Acme", "acme", null, Stage.REJECTED, null, null);
		long source = apps.insert("Acme2", "acme2", null, Stage.ONLINE_TEST, null, null);
		long aid = actions.insert(source, "Do test", null, Stage.ONLINE_TEST, null);
		apps.lock(source, "role");
		jdbc.update("UPDATE applications SET updated_at = '2026-01-01T00:00:00Z' WHERE id = ?", target);

		apps.mergeInto(target, source);

		assertThat(actions.findForApplication(target)).singleElement().satisfies(a -> {
			assertThat(a.id()).isEqualTo(aid);
			assertThat(a.status()).isEqualTo(ApplicationAction.Status.CLOSED);
		});
		assertThat(apps.find(target).orElseThrow().lockedFields()).contains("role");
		assertThat(jdbc.queryForObject("SELECT updated_at FROM applications WHERE id = ?", String.class, target))
				.isNotEqualTo("2026-01-01T00:00:00Z");
	}

	@Test
	void mergeIntoSelfIsRejectedAndDeletesNothing() {
		long id = apps.insert("Acme", "acme", null, Stage.APPLIED, null, null);
		actions.insert(id, "Do test", null, Stage.ONLINE_TEST, null);

		assertThatThrownBy(() -> apps.mergeInto(id, id)).isInstanceOf(IllegalArgumentException.class);

		assertThat(apps.find(id)).isPresent();
		assertThat(actions.findForApplication(id)).hasSize(1);
	}

	@Test
	void mergeIntoMissingTargetIsRejected() {
		long source = apps.insert("Acme", "acme", null, Stage.APPLIED, null, null);
		actions.insert(source, "Do test", null, Stage.ONLINE_TEST, null);

		assertThatThrownBy(() -> apps.mergeInto(99999L, source)).isInstanceOf(IllegalArgumentException.class);

		assertThat(apps.find(source)).isPresent();
		assertThat(actions.findForApplication(source)).hasSize(1);
	}

	@Test
	void mergeIntoMissingSourceIsRejected() {
		long target = apps.insert("Acme", "acme", null, Stage.APPLIED, null, null);

		assertThatThrownBy(() -> apps.mergeInto(target, 99999L)).isInstanceOf(IllegalArgumentException.class);

		assertThat(apps.find(target)).isPresent();
	}

	@Test
	void actionLifecycle() {
		long id = apps.insert("Acme", "acme", null, Stage.APPLIED, null, null);
		OffsetDateTime due = OffsetDateTime.parse("2026-11-01T10:00:00+00:00");
		long a1 = actions.insert(id, "Test", due, Stage.ONLINE_TEST, null);
		actions.insert(id, "Interview", null, Stage.INTERVIEW, null);

		assertThat(actions.existsOpen(id, "Test", due)).isTrue();
		assertThat(actions.existsOpen(id, "Other", due)).isFalse();
		assertThat(actions.findOpen()).hasSize(2);
		assertThat(actions.findOpen().stream().filter(x -> x.id() == a1).findFirst().orElseThrow().dueAt())
				.isEqualTo(due);

		actions.markDone(a1);
		assertThat(actions.existsOpen(id, "Test", due)).isFalse();
		actions.closeOpenForStage(id, Stage.INTERVIEW);
		assertThat(actions.findOpen()).isEmpty();
		assertThat(actions.findForApplication(id)).extracting(ApplicationAction::status)
				.containsExactlyInAnyOrder(ApplicationAction.Status.DONE, ApplicationAction.Status.CLOSED);
	}

	@Test
	void sameInstantAtDifferentOffsetsIsSameAction() {
		long id = apps.insert("Acme", "acme", null, Stage.APPLIED, null, null);
		actions.insert(id, "Test", OffsetDateTime.parse("2026-11-01T10:00:00+01:00"), Stage.ONLINE_TEST, null);

		assertThat(actions.existsOpen(id, "Test", OffsetDateTime.parse("2026-11-01T09:00:00Z"))).isTrue();
		assertThat(actions.existsOpen(id, "Test", OffsetDateTime.parse("2026-11-01T11:00:00+02:00"))).isTrue();
		assertThat(actions.existsOpen(id, "Test", OffsetDateTime.parse("2026-11-01T10:00:00Z"))).isFalse();
	}
}
