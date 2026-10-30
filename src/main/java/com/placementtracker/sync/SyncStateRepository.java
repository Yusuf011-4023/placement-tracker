package com.placementtracker.sync;

import java.time.Instant;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.placementtracker.mail.MailAccount;

@Repository
public class SyncStateRepository {

	private final JdbcClient jdbc;

	public SyncStateRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** Returns the stored state, or a NOT_CONNECTED placeholder when the account has never synced. */
	public SyncState get(MailAccount a) {
		return jdbc.sql("SELECT high_water, last_success_at, status, last_error FROM sync_state WHERE account = ?")
				.param(a.name())
				.query((rs, row) -> {
					String hw = rs.getString("high_water");
					String ok = rs.getString("last_success_at");
					return new SyncState(a, hw == null ? null : Instant.parse(hw), ok == null ? null : Instant.parse(ok),
							SyncStatus.valueOf(rs.getString("status")), rs.getString("last_error"));
				}).optional()
				.orElse(new SyncState(a, null, null, SyncStatus.NOT_CONNECTED, null));
	}

	public void markSuccess(MailAccount a, Instant highWater, Instant at) {
		jdbc.sql("INSERT INTO sync_state (account, high_water, last_success_at, status, last_error) "
				+ "VALUES (?, ?, ?, 'OK', NULL) ON CONFLICT (account) DO UPDATE SET high_water = excluded.high_water, "
				+ "last_success_at = excluded.last_success_at, status = 'OK', last_error = NULL")
				.params(a.name(), highWater == null ? null : highWater.toString(), at.toString()).update();
	}

	public void markError(MailAccount a, SyncStatus s, String error) {
		jdbc.sql("INSERT INTO sync_state (account, status, last_error) VALUES (?, ?, ?) "
				+ "ON CONFLICT (account) DO UPDATE SET status = excluded.status, last_error = excluded.last_error")
				.params(a.name(), s.name(), error).update();
	}

	/** After a successful sign-in: a NEEDS_RECONNECT state no longer applies (the next sync will confirm). */
	public void clearNeedsReconnect(MailAccount a) {
		jdbc.sql("UPDATE sync_state SET status = 'OK', last_error = NULL WHERE account = ? AND status = 'NEEDS_RECONNECT'")
				.param(a.name()).update();
	}
}
