package com.placementtracker.application;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;

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
