package com.placementtracker.web;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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
	private final AtomicBoolean connecting = new AtomicBoolean();
	private final Map<String, String> lastConnectErrors = new ConcurrentHashMap<>();
	private final ExecutorService syncExecutor = Executors.newSingleThreadExecutor(r -> daemon(r, "sync-runner"));
	private final ExecutorService connectExecutor = Executors.newSingleThreadExecutor(r -> daemon(r, "mail-connect"));

	public SyncRunner(SyncService sync, ApplicationEventPublisher events) {
		this.sync = sync;
		this.events = events;
	}

	public boolean isSyncing() {
		return syncing.get();
	}

	public boolean isConnecting() {
		return connecting.get();
	}

	/** Why the last connect of this account failed, or null when it succeeded or another attempt is running. */
	public String lastConnectError(String name) {
		return lastConnectErrors.get(name);
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

	/**
	 * Runs a (possibly slow, browser-opening) connect unless one is already running; returns whether this call
	 * started one. A successful connect is followed by a sync; a failed one is kept as the account's last error.
	 */
	public boolean connectAsync(String name, ConnectAction connect) {
		if (!connecting.compareAndSet(false, true)) {
			return false;
		}
		lastConnectErrors.remove(name);
		try {
			connectExecutor.execute(() -> {
				try {
					connect.run();
					trigger();
				}
				catch (Exception e) {
					String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
					log.warn("Connecting {} failed: {}", name, message);
					lastConnectErrors.put(name, message);
				}
				finally {
					connecting.set(false);
				}
			});
		}
		catch (RuntimeException e) {
			connecting.set(false);
			throw e;
		}
		return true;
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
