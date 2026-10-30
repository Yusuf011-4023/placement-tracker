package com.placementtracker.notify;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.placementtracker.sync.SyncCompletedEvent;
import com.placementtracker.sync.SyncReport;

class SyncNotificationListenerTest extends NotifyTestBase {

	SyncNotificationListener listener() {
		return new SyncNotificationListener(actions, apps, log, notifier);
	}

	static SyncCompletedEvent event(int review, List<Long> ids) {
		return new SyncCompletedEvent(new SyncReport(0, 0, 0, 0, review, ids, Map.of(), true));
	}

	@Test
	void newActionsBatchedIntoOneNotification() {
		long n = app("Northwind Bank");
		long b = app("Brightline Energy");
		long c = app("Cobalt");
		long x = action(n, "Complete online test", null);
		long y = action(b, "Book interview", null);
		long z = action(c, "Send CV", null);
		listener().onSync(event(0, List.of(x, y, z)));
		assertThat(notifier.sent).hasSize(1);
		assertThat(notifier.sent.get(0)[0]).isEqualTo("New to-dos");
		assertThat(notifier.sent.get(0)[1]).isEqualTo(
				"Northwind Bank: Complete online test; Brightline Energy: Book interview; Cobalt: Send CV");
		listener().onSync(event(0, List.of(x, y, z)));
		assertThat(notifier.sent).hasSize(1);
	}

	@Test
	void moreThanThreeAddsMoreSuffix() {
		long n = app("Northwind Bank");
		long a = action(n, "A", null);
		long b = action(n, "B", null);
		long c = action(n, "C", null);
		long d = action(n, "D", null);
		listener().onSync(event(0, List.of(a, b, c, d)));
		assertThat(notifier.sent.get(0)[1]).endsWith("Northwind Bank: C +1 more");
	}

	@Test
	void newReviewItemsBatchedIntoOneNotification() {
		listener().onSync(event(3, List.of()));
		assertThat(notifier.sent).hasSize(1);
		assertThat(notifier.sent.get(0)[0]).isEqualTo("Please check");
		assertThat(notifier.sent.get(0)[1]).isEqualTo("3 emails need checking");
	}

	@Test
	void noNotificationWhenNothingNew() {
		listener().onSync(event(0, List.of()));
		assertThat(notifier.sent).isEmpty();
	}

	@Test
	void staleOrClosedActionIdsAreSkipped() {
		long n = app("Northwind Bank");
		long closed = action(n, "Closed", null);
		long open = action(n, "Open one", null);
		jdbc.update("UPDATE actions SET status = 'CLOSED' WHERE id = ?", closed);
		listener().onSync(event(0, List.of(closed, 9999L, open)));
		assertThat(notifier.sent).hasSize(1);
		assertThat(notifier.sent.get(0)[1]).isEqualTo("Northwind Bank: Open one");
	}

	@Test
	void newActionsNotMarkedWhenBatchSendFails() {
		long n = app("Northwind Bank");
		long x = action(n, "One", null);
		long y = action(n, "Two", null);
		notifier.refuse = true;
		listener().onSync(event(0, List.of(x, y)));
		assertThat(log.seen("action:" + x + ":new")).isFalse();
		assertThat(log.seen("action:" + y + ":new")).isFalse();
		notifier.sent.clear();
		notifier.refuse = false;
		listener().onSync(event(0, List.of(x, y)));
		assertThat(notifier.sent).hasSize(1);
		assertThat(log.seen("action:" + x + ":new")).isTrue();
	}
}
