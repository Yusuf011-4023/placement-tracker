package com.placementtracker.application;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class HistoryRepository {

	private final JdbcClient jdbc;

	public HistoryRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	public void record(long appId, Stage from, Stage to, String source, Long emailId) {
		jdbc.sql("INSERT INTO stage_history (application_id, from_stage, to_stage, changed_at, source, email_id) "
				+ "VALUES (?, ?, ?, ?, ?, ?)")
				.params(appId, from == null ? null : from.name(), to.name(), Instant.now().toString(), source, emailId)
				.update();
	}

	/**
	 * The received time of the email behind the application's latest EMAIL-sourced stage change (including its
	 * creation), if that email is known and dated.
	 */
	public Optional<Instant> latestEmailChangeReceivedAt(long appId) {
		return jdbc.sql("SELECT e.received_at FROM stage_history h JOIN emails e ON e.id = h.email_id "
				+ "WHERE h.application_id = ? AND h.source = 'EMAIL' ORDER BY h.id DESC LIMIT 1")
				.param(appId).query((rs, row) -> rs.getString(1)).optional()
				.filter(v -> v != null && !v.isBlank()).map(Instant::parse);
	}

	public List<StageChange> forApplication(long appId) {
		return jdbc.sql("SELECT id, application_id, from_stage, to_stage, changed_at, source, email_id "
				+ "FROM stage_history WHERE application_id = ? ORDER BY id").param(appId).query(this::map).list();
	}

	private StageChange map(ResultSet rs, int row) throws SQLException {
		String from = rs.getString("from_stage");
		long email = rs.getLong("email_id");
		Long emailId = rs.wasNull() ? null : email;
		return new StageChange(rs.getLong("id"), rs.getLong("application_id"),
				from == null ? null : Stage.valueOf(from), Stage.valueOf(rs.getString("to_stage")),
				Instant.parse(rs.getString("changed_at")), rs.getString("source"), emailId);
	}
}
