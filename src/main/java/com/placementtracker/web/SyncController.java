package com.placementtracker.web;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import com.placementtracker.application.NotFoundException;
import com.placementtracker.mail.MailSource;
import com.placementtracker.sync.SyncStateRepository;
import com.placementtracker.web.dto.Dtos.Account;

/** Sync trigger plus the mail accounts (list and connect). */
@RestController
public class SyncController {

	private final SyncRunner runner;
	private final AccountViews accounts;
	private final SyncStateRepository syncState;

	public SyncController(SyncRunner runner, AccountViews accounts, SyncStateRepository syncState) {
		this.runner = runner;
		this.accounts = accounts;
		this.syncState = syncState;
	}

	@PostMapping("/api/sync")
	public ResponseEntity<Map<String, Object>> sync() {
		boolean started = runner.trigger();
		return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of("started", started, "syncing", true));
	}

	@GetMapping("/api/accounts")
	public List<Account> accounts() {
		return accounts.list();
	}

	@PostMapping("/api/accounts/{account}/connect")
	public ResponseEntity<Map<String, Object>> connect(@PathVariable String account) {
		MailSource source = accounts.find(account);
		if (source == null) {
			throw new NotFoundException("No such account: " + account);
		}
		source.setupProblem().ifPresent(problem -> {
			throw new IllegalStateException(problem); // 409, shown on the setup page
		});
		// Single-flight: a second press while the sign-in is open does not start another one.
		boolean started = runner.connectAsync(source.account().name(), () -> {
			source.connect();
			syncState.clearNeedsReconnect(source.account());
		});
		return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of("connecting", true, "started", started));
	}
}
