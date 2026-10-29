package com.placementtracker.mail;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class EmailRepository {

	private static final String COLUMNS = "id, account, message_id, thread_id, dedupe_key, from_address, subject, "
			+ "received_at, web_link, application_id, status, attempts";

	private final JdbcClient jdbc;

	public EmailRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** Inserts as PENDING; empty when (account, message_id) already exists. */
	public Optional<EmailRecord> insertIfNew(MailMessage m, String dedupeKey) {
		KeyHolder keys = new GeneratedKeyHolder();
		int rows = jdbc.sql("INSERT INTO emails (account, message_id, thread_id, dedupe_key, from_address, subject, "
				+ "received_at, web_link, status, attempts) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', 0) "
				+ "ON CONFLICT (account, message_id) DO NOTHING")
				.params(m.account().name(), m.messageId(), m.threadId(), dedupeKey, m.from(), m.subject(),
						m.receivedAt() == null ? null : m.receivedAt().toString(), m.webLink())
				.update(keys);
		if (rows == 0) {
			return Optional.empty();
		}
		return find(keys.getKey().longValue());
	}

	public boolean existsDedupeKey(String key, long excludingId) {
		Integer n = jdbc.sql("SELECT COUNT(*) FROM emails WHERE dedupe_key = ? AND id <> ?")
				.params(key, excludingId).query(Integer.class).single();
		return n > 0;
	}

	public void setStatus(long id, EmailStatus s) {
		jdbc.sql("UPDATE emails SET status = ? WHERE id = ?").params(s.name(), id).update();
	}

	public void linkApplication(long id, long appId) {
		jdbc.sql("UPDATE emails SET application_id = ? WHERE id = ?").params(appId, id).update();
	}

	@Transactional
	public int recordFailure(long id, String error) {
		jdbc.sql("UPDATE emails SET attempts = attempts + 1, last_error = ? WHERE id = ?").params(error, id).update();
		return jdbc.sql("SELECT attempts FROM emails WHERE id = ?").param(id).query(Integer.class).single();
	}

	public List<EmailRecord> findRetryable(int maxAttempts) {
		return jdbc.sql("SELECT " + COLUMNS + " FROM emails WHERE status = 'FAILED' AND attempts < ? ORDER BY id")
				.param(maxAttempts).query(this::map).list();
	}

	public Optional<EmailRecord> find(long id) {
		return jdbc.sql("SELECT " + COLUMNS + " FROM emails WHERE id = ?").param(id).query(this::map).optional();
	}

	private EmailRecord map(ResultSet rs, int row) throws SQLException {
		long app = rs.getLong("application_id");
		Long appId = rs.wasNull() ? null : app;
		String received = rs.getString("received_at");
		return new EmailRecord(rs.getLong("id"), MailAccount.valueOf(rs.getString("account")),
				rs.getString("message_id"), rs.getString("thread_id"), rs.getString("dedupe_key"),
				rs.getString("from_address"), rs.getString("subject"),
				received == null ? null : java.time.Instant.parse(received), rs.getString("web_link"), appId,
				EmailStatus.valueOf(rs.getString("status")), rs.getInt("attempts"));
	}
}
