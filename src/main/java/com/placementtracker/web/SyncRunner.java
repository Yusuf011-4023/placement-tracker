package com.placementtracker.web;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import com.placementtracker.sync.SyncCompletedEvent;
import com.placementtracker.sync.SyncService;

import jakarta.annotation.PreDestroy;

/** Runs syncs and mail connections in the background so the API can answer straight away. */
@Component
public class SyncRunner {

	private static final Logger log = LoggerFactory.getLogger(SyncRunner.class);

	private final SyncService sync;
	private final ApplicationEventPublisher events;
	private final AtomicBoolean syncing = new AtomicBoolean();
	private final ExecutorService syncExecutor = Executors.newSingleThreadExecutor(r -> daemon(r, "sync-runner"));
	private final ExecutorService connectExecutor = Executors.newCachedThreadPool(r -> daemon(r, "mail-connect"));

	public SyncRunner(SyncService sync, ApplicationEventPublisher events) {
		this.sync = sync;
		this.events = events;
	}

	public boolean isSyncing() {
		return syncing.get();
	}

	/** Starts a sync unless one is already running; returns whether this call started one. */
	public boolean trigger() {
		if (!syncing.compareAndSet(false, true)) {
			return false;
		}
		try {
			syncExecutor.execute(() -> {
				try {
					events.publishEvent(new SyncCompletedEvent(sync.syncAll()));
				}
				catch (RuntimeException e) {
					log.error("Manual sync failed", e);
				}
				finally {
					syncing.set(false);
				}
			});
		}
		catch (RuntimeException e) {
			syncing.set(false);
			throw e;
		}
		return true;
	}

	/** Runs a (possibly slow, browser-opening) connect; a successful one is followed by a sync. */
	public void connectAsync(String name, ConnectAction connect) {
		connectExecutor.execute(() -> {
			try {
				connect.run();
				trigger();
			}
			catch (Exception e) {
				log.warn("Connecting {} failed: {}", name, e.getMessage());
			}
		});
	}

	@FunctionalInterface
	public interface ConnectAction {
		void run() throws Exception;
	}

	@PreDestroy
	void shutdown() {
		syncExecutor.shutdownNow();
		connectExecutor.shutdownNow();
	}

	private static Thread daemon(Runnable r, String name) {
		Thread t = new Thread(r, name);
		t.setDaemon(true);
		return t;
	}
}
