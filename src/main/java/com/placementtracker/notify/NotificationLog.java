package com.placementtracker.notify;

import java.time.Instant;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class NotificationLog {

	private final JdbcClient jdbc;

	public NotificationLog(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** True only the first time a key is seen. */
	public boolean markIfNew(String key) {
		return jdbc.sql("INSERT INTO notifications_sent (key, sent_at) VALUES (?, ?) ON CONFLICT (key) DO NOTHING")
				.params(key, Instant.now().toString()).update() > 0;
	}
}
