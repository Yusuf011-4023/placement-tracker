package com.placementtracker.application;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.placementtracker.matching.CompanyNames;

@Repository
public class ApplicationRepository {

	private static final String COLUMNS =
			"id, company, company_key, role, stage, interview_rounds, applied_on, notes";

	private final JdbcClient jdbc;

	public ApplicationRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	public long insert(String company, String companyKey, String role, Stage stage, LocalDate appliedOn,
			String notes) {
		String now = Instant.now().toString();
		KeyHolder keys = new GeneratedKeyHolder();
		jdbc.sql("INSERT INTO applications (company, company_key, role, stage, interview_rounds, applied_on, "
				+ "notes, created_at, updated_at) VALUES (?, ?, ?, ?, 0, ?, ?, ?, ?)")
				.params(company, companyKey, role, stage.name(), appliedOn == null ? null : appliedOn.toString(),
						notes, now, now)
				.update(keys);
		return keys.getKey().longValue();
	}

	public Optional<Application> find(long id) {
		return jdbc.sql("SELECT " + COLUMNS + " FROM applications WHERE id = ?").param(id)
				.query(this::map).optional();
	}

	public List<Application> findByCompanyKey(String key) {
		return jdbc.sql("SELECT " + COLUMNS + " FROM applications WHERE company_key = ? ORDER BY id").param(key)
				.query(this::map).list();
	}

	public List<Application> findAll() {
		return jdbc.sql("SELECT " + COLUMNS + " FROM applications ORDER BY id").query(this::map).list();
	}

	public void updateStage(long id, Stage stage) {
		jdbc.sql("UPDATE applications SET stage = ?, updated_at = ? WHERE id = ?")
				.params(stage.name(), Instant.now().toString(), id).update();
	}

	public void incrementInterviewRounds(long id) {
		jdbc.sql("UPDATE applications SET interview_rounds = interview_rounds + 1, updated_at = ? WHERE id = ?")
				.params(Instant.now().toString(), id).update();
	}

	/** Updates company, role, notes and/or appliedOn; company changes recompute company_key. */
	public void updateFields(long id, Map<String, Object> fields) {
		List<String> sets = new ArrayList<>();
		List<Object> args = new ArrayList<>();
		for (Map.Entry<String, Object> e : fields.entrySet()) {
			Object v = e.getValue();
			switch (e.getKey()) {
				case "company" -> {
					sets.add("company = ?");
					args.add(v);
					sets.add("company_key = ?");
					args.add(CompanyNames.key((String) v));
				}
				case "role" -> {
					sets.add("role = ?");
					args.add(v);
				}
				case "notes" -> {
					sets.add("notes = ?");
					args.add(v);
				}
				case "appliedOn" -> {
					sets.add("applied_on = ?");
					args.add(v == null ? null : v.toString());
				}
				default -> throw new IllegalArgumentException("Unsupported field: " + e.getKey());
			}
		}
		if (sets.isEmpty()) {
			return;
		}
		sets.add("updated_at = ?");
		args.add(Instant.now().toString());
		args.add(id);
		jdbc.sql("UPDATE applications SET " + String.join(", ", sets) + " WHERE id = ?").params(args).update();
	}

	public void lock(long id, String field) {
		jdbc.sql("INSERT OR IGNORE INTO field_locks (application_id, field) VALUES (?, ?)")
				.params(id, field).update();
	}

	public Optional<Long> findByThreadId(String account, String threadId) {
		return jdbc.sql("SELECT application_id FROM emails WHERE account = ? AND thread_id = ? "
				+ "AND application_id IS NOT NULL ORDER BY id LIMIT 1")
				.params(account, threadId).query(Long.class).optional();
	}

	@Transactional
	public void mergeInto(long targetId, long sourceId) {
		jdbc.sql("UPDATE emails SET application_id = ? WHERE application_id = ?").params(targetId, sourceId).update();
		jdbc.sql("UPDATE actions SET application_id = ? WHERE application_id = ?").params(targetId, sourceId).update();
		jdbc.sql("UPDATE stage_history SET application_id = ? WHERE application_id = ?")
				.params(targetId, sourceId).update();
		jdbc.sql("UPDATE review_items SET application_id = ? WHERE application_id = ?")
				.params(targetId, sourceId).update();
		jdbc.sql("DELETE FROM field_locks WHERE application_id = ?").param(sourceId).update();
		jdbc.sql("DELETE FROM applications WHERE id = ?").param(sourceId).update();
	}

	private Application map(ResultSet rs, int row) throws SQLException {
		long id = rs.getLong("id");
		String applied = rs.getString("applied_on");
		Set<String> locked = new HashSet<>(
				jdbc.sql("SELECT field FROM field_locks WHERE application_id = ?").param(id).query(String.class).list());
		return new Application(id, rs.getString("company"), rs.getString("company_key"), rs.getString("role"),
				Stage.valueOf(rs.getString("stage")), rs.getInt("interview_rounds"),
				applied == null ? null : LocalDate.parse(applied), rs.getString("notes"), Set.copyOf(locked));
	}
}
