package com.placementtracker.web;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.placementtracker.application.ActionRepository;
import com.placementtracker.application.Application;
import com.placementtracker.application.ApplicationEditService;
import com.placementtracker.application.ApplicationRepository;
import com.placementtracker.application.HistoryRepository;
import com.placementtracker.application.NotFoundException;
import com.placementtracker.application.Stage;
import com.placementtracker.application.StageChange;
import com.placementtracker.mail.EmailRecord;
import com.placementtracker.mail.EmailRepository;
import com.placementtracker.web.dto.Dtos.ActionView;
import com.placementtracker.web.dto.Dtos.ApplicationDetail;
import com.placementtracker.web.dto.Dtos.EmailRef;
import com.placementtracker.web.dto.Dtos.HistoryEntry;
import com.placementtracker.web.dto.Dtos.MergeRequest;
import com.placementtracker.web.dto.Dtos.NewAction;
import com.placementtracker.web.dto.Dtos.NewApplication;

@RestController
public class ApplicationController {

	private final ApplicationRepository apps;
	private final ActionRepository actions;
	private final HistoryRepository history;
	private final EmailRepository emails;
	private final ApplicationEditService edits;

	public ApplicationController(ApplicationRepository apps, ActionRepository actions, HistoryRepository history,
			EmailRepository emails, ApplicationEditService edits) {
		this.apps = apps;
		this.actions = actions;
		this.history = history;
		this.emails = emails;
		this.edits = edits;
	}

	@GetMapping("/api/applications/{id}")
	public ApplicationDetail get(@PathVariable long id) {
		return detail(id);
	}

	@PostMapping("/api/applications")
	public ResponseEntity<ApplicationDetail> create(@RequestBody NewApplication body) {
		Stage stage = null;
		if (body.stage() != null && !body.stage().isBlank()) {
			try {
				stage = Stage.valueOf(body.stage());
			}
			catch (IllegalArgumentException e) {
				throw new IllegalArgumentException("Unknown stage: " + body.stage());
			}
		}
		long id = edits.create(body.company(), body.role(), stage, body.appliedOn(), body.notes());
		return ResponseEntity.status(HttpStatus.CREATED).body(detail(id));
	}

	@PatchMapping("/api/applications/{id}")
	public ApplicationDetail patch(@PathVariable long id, @RequestBody Map<String, Object> body) {
		edits.patch(id, body);
		return detail(id);
	}

	@PostMapping("/api/applications/{id}/actions")
	public ResponseEntity<Map<String, Object>> addAction(@PathVariable long id, @RequestBody NewAction body) {
		long actionId = edits.addAction(id, body.description(), body.dueAt());
		return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("actionId", actionId));
	}

	@PostMapping("/api/actions/{id}/done")
	public Map<String, Object> done(@PathVariable long id) {
		edits.markDone(id);
		return Map.of("ok", true);
	}

	@PostMapping("/api/applications/{targetId}/merge")
	public ApplicationDetail merge(@PathVariable long targetId, @RequestBody MergeRequest body) {
		if (body.sourceId() == null) {
			throw new IllegalArgumentException("sourceId is required");
		}
		apps.mergeInto(targetId, body.sourceId());
		return detail(targetId);
	}

	private ApplicationDetail detail(long id) {
		Application a = apps.find(id).orElseThrow(() -> new NotFoundException("No such application: " + id));
		List<ActionView> acts = actions.findForApplication(id).stream()
				.map(x -> new ActionView(x.id(), x.description(), x.dueAt(), x.status().name(), x.stage().name()))
				.toList();
		List<HistoryEntry> hist = history.forApplication(id).stream().map(this::entry).toList();
		return new ApplicationDetail(a.id(), a.company(), a.role(), a.stage().name(), a.interviewRounds(),
				a.appliedOn(), a.notes(), a.lockedFields().stream().sorted().toList(), acts, hist);
	}

	private HistoryEntry entry(StageChange c) {
		EmailRef ref = null;
		if (c.emailId() != null) {
			EmailRecord e = emails.find(c.emailId()).orElse(null);
			if (e != null) {
				ref = new EmailRef(e.subject(), e.webLink());
			}
		}
		return new HistoryEntry(c.fromStage() == null ? null : c.fromStage().name(), c.toStage().name(),
				c.changedAt(), c.source(), ref);
	}
}
