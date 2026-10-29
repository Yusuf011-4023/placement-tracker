package com.placementtracker.notify;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

class ReminderServiceTest extends NotifyTestBase {

	static final Instant NOW = Instant.parse("2026-10-29T12:00:00Z");
	static final OffsetDateTime NOW_ODT = OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC);

	ReminderService service() {
		return new ReminderService(actions, apps, log, notifier, Clock.fixed(NOW, ZoneOffset.UTC));
	}

	@Test
	void threeDayReminderOnce() {
		long a = app("Northwind Bank");
		action(a, "Complete online test", NOW_ODT.plusHours(70));
		ReminderService s = service();
		s.run();
		assertThat(notifier.sent).hasSize(1);
		assertThat(notifier.sent.get(0)[0]).isEqualTo("Due in 3 days");
		assertThat(notifier.sent.get(0)[1]).isEqualTo("Northwind Bank: Complete online test — due Sun 1 Nov 10:00");
		s.run();
		assertThat(notifier.sent).hasSize(1);
	}

	@Test
	void oneDayReminderOnce() {
		long a = app("Northwind Bank");
		action(a, "Complete online test", NOW_ODT.plusHours(20));
		ReminderService s = service();
		s.run();
		s.run();
		assertThat(notifier.sent).hasSize(1);
		assertThat(notifier.sent.get(0)[0]).isEqualTo("Due tomorrow");
	}

	@Test
	void lateStartSendsOnlyOneDayReminder() {
		long a = app("Northwind Bank");
		long id = action(a, "Complete online test", NOW_ODT.plusHours(20));
		service().run();
		assertThat(notifier.sent).hasSize(1);
		assertThat(notifier.sent.get(0)[0]).isEqualTo("Due tomorrow");
		assertThat(log.markIfNew("action:" + id + ":3d")).isFalse();
	}

	@Test
	void remindersNotResentAfterRestart() {
		long a = app("Northwind Bank");
		action(a, "Complete online test", NOW_ODT.plusHours(70));
		service().run();
		service().run();
		assertThat(notifier.sent).hasSize(1);
	}

	@Test
	void doneClosedAndOverdueNeverRemind() {
		long a = app("Northwind Bank");
		long done = action(a, "Done one", NOW_ODT.plusHours(20));
		long closed = action(a, "Closed one", NOW_ODT.plusHours(20));
		action(a, "Overdue one", NOW_ODT.minusHours(2));
		action(a, "Far away", NOW_ODT.plusHours(100));
		action(a, "No due date", null);
		actions.markDone(done);
		jdbc.update("UPDATE actions SET status = 'CLOSED' WHERE id = ?", closed);
		service().run();
		assertThat(notifier.sent).isEmpty();
	}

	@Test
	void reminderNotMarkedWhenNotifierCannotShow() {
		long a = app("Northwind Bank");
		action(a, "Complete online test", NOW_ODT.plusHours(70));
		ReminderService s = service();
		notifier.refuse = true;
		s.run();
		notifier.sent.clear();
		notifier.refuse = false;
		s.run();
		assertThat(notifier.sent).hasSize(1);
		s.run();
		assertThat(notifier.sent).hasSize(1);
	}
}
