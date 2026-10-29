package com.placementtracker.application;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.placementtracker.matching.CompanyNames;

/** Changes the owner makes by hand. Every edited field is locked so later emails cannot overwrite it. */
@Service
public class ApplicationEditService {

	private static final String SOURCE_USER = "USER";
	private static final Set<String> FIELDS = Set.of("company", "role", "notes", "appliedOn", "stage");

	private final ApplicationRepository apps;
	private final ActionRepository actions;
	private final HistoryRepository history;
	private final StageMover stageMover;

	public ApplicationEditService(ApplicationRepository apps, ActionRepository actions, HistoryRepository history,
			StageMover stageMover) {
		this.apps = apps;
		this.actions = actions;
		this.history = history;
		this.stageMover = stageMover;
	}

	@Transactional
	public long create(String company, String role, Stage stage, LocalDate appliedOn, String notes) {
		if (company == null || company.isBlank()) {
			throw new IllegalArgumentException("company is required");
		}
		Stage s = stage == null ? Stage.APPLIED : stage;
		long id = apps.insert(company.trim(), CompanyNames.key(company), blankToNull(role), s, appliedOn,
				blankToNull(notes));
		history.record(id, null, s, SOURCE_USER, null);
		if (s == Stage.INTERVIEW) {
			apps.incrementInterviewRounds(id);
		}
		return id;
	}

	/** Applies any of company/role/stage/notes/appliedOn; each field that really changes is locked. */
	@Transactional
	public void patch(long id, Map<String, Object> body) {
		Application app = apps.find(id).orElseThrow(() -> new NotFoundException("No such application: " + id));
		for (String key : body.keySet()) {
			if (!FIELDS.contains(key)) {
				throw new IllegalArgumentException("Unsupported field: " + key);
			}
		}
		Map<String, Object> changed = new LinkedHashMap<>();
		if (body.containsKey("company")) {
			String v = text(body, "company");
			if (v == null || v.isBlank()) {
				throw new IllegalArgumentException("company cannot be blank");
			}
			changed.put("company", v.trim());
		}
		if (body.containsKey("role")) {
			changed.put("role", blankToNull(text(body, "role")));
		}
		if (body.containsKey("notes")) {
			changed.put("notes", blankToNull(text(body, "notes")));
		}
		if (body.containsKey("appliedOn")) {
			changed.put("appliedOn", parseDate(text(body, "appliedOn")));
		}
		Stage newStage = null;
		if (body.containsKey("stage")) {
			String raw = text(body, "stage");
			if (raw == null) {
				throw new IllegalArgumentException("stage cannot be null");
			}
			try {
				newStage = Stage.valueOf(raw);
			}
			catch (IllegalArgumentException e) {
				throw new IllegalArgumentException("Unknown stage: " + raw);
			}
		}
		Map<String, Object> current = new LinkedHashMap<>();
		current.put("company", app.company());
		current.put("role", app.role());
		current.put("notes", app.notes());
		current.put("appliedOn", app.appliedOn());
		changed.entrySet().removeIf(e -> Objects.equals(e.getValue(), current.get(e.getKey())));
		if (!changed.isEmpty()) {
			apps.updateFields(id, changed);
			changed.keySet().forEach(f -> apps.lock(id, f));
		}
		if (newStage != null && newStage != app.stage()) {
			changeStageByUser(app, newStage);
		}
	}

	private void changeStageByUser(Application app, Stage stage) {
		stageMover.move(app, stage, SOURCE_USER, null, true);
		apps.lock(app.id(), "stage");
	}

	@Transactional
	public long addAction(long appId, String description, OffsetDateTime dueAt) {
		Application app = apps.find(appId).orElseThrow(() -> new NotFoundException("No such application: " + appId));
		if (app.stage().isClosed()) {
			throw new IllegalStateException("Cannot add a to-do to a closed application");
		}
		if (description == null || description.isBlank()) {
			throw new IllegalArgumentException("description is required");
		}
		return actions.insert(appId, description.trim(), dueAt, app.stage(), null);
	}

	@Transactional
	public void markDone(long actionId) {
		actions.find(actionId).orElseThrow(() -> new NotFoundException("No such action: " + actionId));
		actions.markDone(actionId);
	}

	private static String text(Map<String, Object> body, String key) {
		Object v = body.get(key);
		if (v == null) {
			return null;
		}
		if (!(v instanceof String s)) {
			throw new IllegalArgumentException(key + " must be text");
		}
		return s;
	}

	private static LocalDate parseDate(String v) {
		if (v == null || v.isBlank()) {
			return null;
		}
		try {
			return LocalDate.parse(v.trim());
		}
		catch (DateTimeException e) {
			throw new IllegalArgumentException("appliedOn must be a date like 2026-10-29");
		}
	}

	private static String blankToNull(String s) {
		return s == null || s.isBlank() ? null : s.trim();
	}
}
