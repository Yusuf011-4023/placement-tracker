package com.placementtracker.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import com.placementtracker.sync.SyncReport;
import com.placementtracker.sync.SyncService;

class SyncRunnerTest {

	private static SyncReport report() {
		return new SyncReport(0, 0, 0, 0, 0, List.of(), Map.of(), false);
	}

	private static void await(java.util.function.BooleanSupplier c) throws InterruptedException {
		for (int i = 0; i < 250 && !c.getAsBoolean(); i++) {
			Thread.sleep(40);
		}
		assertThat(c.getAsBoolean()).isTrue();
	}

	private static void awaitIdle(SyncRunner runner) throws InterruptedException {
		for (int i = 0; i < 250 && runner.isSyncing(); i++) {
			Thread.sleep(40);
		}
	}

	@Test
	void secondTriggerWhileRunningIsNotQueued() throws Exception {
		SyncService sync = mock(SyncService.class);
		CountDownLatch release = new CountDownLatch(1);
		java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
		when(sync.syncAll()).thenAnswer(inv -> {
			calls.incrementAndGet();
			release.await(5, TimeUnit.SECONDS);
			return report();
		});
		SyncRunner runner = new SyncRunner(sync, mock(ApplicationEventPublisher.class));

		assertThat(runner.trigger()).isTrue();
		assertThat(runner.isSyncing()).isTrue();
		assertThat(runner.trigger()).isFalse();
		release.countDown();
		awaitIdle(runner);
		assertThat(runner.isSyncing()).isFalse();
		assertThat(calls.get()).isEqualTo(1);
		assertThat(runner.trigger()).isTrue();
		await(() -> calls.get() == 2);
	}

	@Test
	void failedSyncClearsFlag() throws Exception {
		SyncService sync = mock(SyncService.class);
		CountDownLatch called = new CountDownLatch(1);
		when(sync.syncAll()).thenAnswer(inv -> {
			called.countDown();
			throw new RuntimeException("boom");
		});
		SyncRunner runner = new SyncRunner(sync, mock(ApplicationEventPublisher.class));

		runner.trigger();
		assertThat(called.await(10, TimeUnit.SECONDS)).isTrue();
		awaitIdle(runner);
		assertThat(runner.isSyncing()).isFalse();
	}

	@Test
	void successfulConnectTriggersSync() throws Exception {
		SyncService sync = mock(SyncService.class);
		CountDownLatch called = new CountDownLatch(1);
		when(sync.syncAll()).thenAnswer(inv -> {
			called.countDown();
			return report();
		});
		SyncRunner runner = new SyncRunner(sync, mock(ApplicationEventPublisher.class));

		runner.connectAsync("GMAIL", () -> { });
		assertThat(called.await(10, TimeUnit.SECONDS)).isTrue();
	}

	@Test
	void failedConnectDoesNotSync() throws Exception {
		SyncService sync = mock(SyncService.class);
		SyncRunner runner = new SyncRunner(sync, mock(ApplicationEventPublisher.class));
		CountDownLatch ran = new CountDownLatch(1);

		runner.connectAsync("GMAIL", () -> {
			ran.countDown();
			throw new IllegalStateException("denied");
		});
		assertThat(ran.await(2, TimeUnit.SECONDS)).isTrue();
		Thread.sleep(150);
		verify(sync, never()).syncAll();
	}

	@Test
	void connectIsSingleFlight() throws Exception {
		SyncRunner runner = new SyncRunner(mock(SyncService.class), mock(ApplicationEventPublisher.class));
		CountDownLatch release = new CountDownLatch(1);
		java.util.concurrent.atomic.AtomicInteger runs = new java.util.concurrent.atomic.AtomicInteger();
		SyncRunner.ConnectAction slow = () -> {
			runs.incrementAndGet();
			release.await(5, TimeUnit.SECONDS);
		};

		assertThat(runner.connectAsync("GMAIL", slow)).isTrue();
		assertThat(runner.connectAsync("GMAIL", slow)).isFalse();
		release.countDown();
		await(() -> !runner.isConnecting());
		assertThat(runs.get()).isEqualTo(1);
		assertThat(runner.connectAsync("GMAIL", () -> { })).isTrue();
	}

	@Test
	void failedConnectKeepsLastErrorUntilNextAttempt() throws Exception {
		SyncRunner runner = new SyncRunner(mock(SyncService.class), mock(ApplicationEventPublisher.class));

		runner.connectAsync("GMAIL", () -> {
			throw new IllegalStateException("access_denied");
		});
		await(() -> runner.lastConnectError("GMAIL") != null);
		assertThat(runner.lastConnectError("GMAIL")).contains("access_denied");

		CountDownLatch release = new CountDownLatch(1);
		runner.connectAsync("GMAIL", () -> release.await(5, TimeUnit.SECONDS));
		assertThat(runner.lastConnectError("GMAIL")).isNull(); // cleared as soon as a new attempt starts
		release.countDown();
	}

	@Test
	void manualSyncAlsoPublishesEvent() throws Exception {
		SyncService sync = mock(SyncService.class);
		SyncReport report = report();
		when(sync.syncAll()).thenReturn(report);
		ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
		SyncRunner runner = new SyncRunner(sync, events);

		runner.trigger();
		awaitIdle(runner);
		verify(events).publishEvent(new com.placementtracker.sync.SyncCompletedEvent(report));
	}
}
