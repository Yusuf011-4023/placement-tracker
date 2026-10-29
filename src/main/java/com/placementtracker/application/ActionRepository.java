package com.placementtracker.application;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class ActionRepository {

	private static final String COLUMNS = "id, application_id, description, due_at, status, stage";

	private final JdbcClient jdbc;

	public ActionRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	public long insert(long appId, String description, OffsetDateTime dueAt, Stage stage, Long sourceEmailId) {
		KeyHolder keys = new GeneratedKeyHolder();
		jdbc.sql("INSERT INTO actions (application_id, description, due_at, status, stage, source_email_id, "
				+ "created_at) VALUES (?, ?, ?, 'OPEN', ?, ?, ?)")
				.params(appId, description, utc(dueAt), stage.name(), sourceEmailId,
						Instant.now().toString())
				.update(keys);
		return keys.getKey().longValue();
	}

	public boolean existsOpen(long appId, String description, OffsetDateTime dueAt) {
		String due = utc(dueAt);
		Integer n = jdbc.sql("SELECT COUNT(*) FROM actions WHERE application_id = ? AND description = ? "
				+ "AND status = 'OPEN' AND due_at IS ?")
				.params(appId, description, due).query(Integer.class).single();
		return n > 0;
	}

	private static String utc(OffsetDateTime t) {
		return t == null ? null : t.withOffsetSameInstant(ZoneOffset.UTC).toString();
	}

	public List<ApplicationAction> findOpen() {
		return jdbc.sql("SELECT " + COLUMNS + " FROM actions WHERE status = 'OPEN' ORDER BY id")
				.query(this::map).list();
	}

	public List<ApplicationAction> findForApplication(long appId) {
		return jdbc.sql("SELECT " + COLUMNS + " FROM actions WHERE application_id = ? ORDER BY id").param(appId)
				.query(this::map).list();
	}

	public void markDone(long id) {
		jdbc.sql("UPDATE actions SET status = 'DONE' WHERE id = ?").param(id).update();
	}

	public void closeOpenForStage(long appId, Stage stage) {
		jdbc.sql("UPDATE actions SET status = 'CLOSED' WHERE application_id = ? AND stage = ? AND status = 'OPEN'")
				.params(appId, stage.name()).update();
	}

	public void closeAllOpen(long appId) {
		jdbc.sql("UPDATE actions SET status = 'CLOSED' WHERE application_id = ? AND status = 'OPEN'")
				.param(appId).update();
	}

	private ApplicationAction map(ResultSet rs, int row) throws SQLException {
		String due = rs.getString("due_at");
		return new ApplicationAction(rs.getLong("id"), rs.getLong("application_id"), rs.getString("description"),
				due == null ? null : OffsetDateTime.parse(due), ApplicationAction.Status.valueOf(rs.getString("status")),
				Stage.valueOf(rs.getString("stage")));
	}
}
