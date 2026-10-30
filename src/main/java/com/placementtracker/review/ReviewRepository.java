package com.placementtracker.review;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class ReviewRepository {

	private static final String COLUMNS = "id, email_id, application_id, reason, suggestion_json, status";

	private final JdbcClient jdbc;

	public ReviewRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	public long open(Long emailId, Long appId, ReviewReason reason, String suggestionJson) {
		KeyHolder keys = new GeneratedKeyHolder();
		jdbc.sql("INSERT INTO review_items (email_id, application_id, reason, suggestion_json, status, created_at) "
				+ "VALUES (?, ?, ?, ?, 'OPEN', ?)")
				.params(emailId, appId, reason.name(), suggestionJson, Instant.now().toString()).update(keys);
		return keys.getKey().longValue();
	}

	public List<ReviewItem> findOpen() {
		return jdbc.sql("SELECT " + COLUMNS + " FROM review_items WHERE status = 'OPEN' ORDER BY id")
				.query(this::map).list();
	}

	public Optional<ReviewItem> find(long id) {
		return jdbc.sql("SELECT " + COLUMNS + " FROM review_items WHERE id = ?").param(id).query(this::map).optional();
	}

	public void resolve(long id) {
		jdbc.sql("UPDATE review_items SET status = 'RESOLVED' WHERE id = ?").param(id).update();
	}

	private ReviewItem map(ResultSet rs, int row) throws SQLException {
		long e = rs.getLong("email_id");
		Long emailId = rs.wasNull() ? null : e;
		long a = rs.getLong("application_id");
		Long appId = rs.wasNull() ? null : a;
		return new ReviewItem(rs.getLong("id"), emailId, appId, ReviewReason.valueOf(rs.getString("reason")),
				rs.getString("suggestion_json"), rs.getString("status"));
	}
}
