package com.placementtracker.web;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.placementtracker.application.ActionRepository;
import com.placementtracker.application.Application;
import com.placementtracker.application.ApplicationAction;
import com.placementtracker.application.ApplicationRepository;
import com.placementtracker.application.Stage;
import com.placementtracker.classify.AiHealth;
import com.placementtracker.classify.EmailClassifier;
import com.placementtracker.mail.EmailRecord;
import com.placementtracker.mail.EmailRepository;
import com.placementtracker.review.ReviewItem;
import com.placementtracker.sync.SyncService;
import com.placementtracker.review.ReviewRepository;
import com.placementtracker.web.dto.Dtos.Ai;
import com.placementtracker.web.dto.Dtos.Card;
import com.placementtracker.web.dto.Dtos.ChartSlice;
import com.placementtracker.web.dto.Dtos.Dashboard;
import com.placementtracker.web.dto.Dtos.NeedsAction;
import com.placementtracker.web.dto.Dtos.PleaseCheck;
import com.placementtracker.web.dto.Dtos.Progress;
import com.placementtracker.web.dto.Dtos.Urgency;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@RestController
public class DashboardController {

	private static final List<Stage> CHART_STAGES = List.of(Stage.APPLIED, Stage.ONLINE_TEST, Stage.INTERVIEW,
			Stage.ASSESSMENT_CENTRE, Stage.OFFER);
	private static final List<String> BOARD_COLUMNS = List.of("APPLIED", "ONLINE_TEST", "INTERVIEW",
			"ASSESSMENT_CENTRE", "OUTCOME");

	private final ApplicationRepository apps;
	private final ActionRepository actions;
	private final ReviewRepository reviews;
	private final EmailRepository emails;
	private final EmailClassifier classifier;
	private final AiHealth aiHealth;
	private final AccountViews accounts;
	private final SyncRunner syncRunner;
	private final SyncService syncService;
	private final Clock clock;
	private final ObjectMapper json;

	public DashboardController(ApplicationRepository apps, ActionRepository actions, ReviewRepository reviews,
			EmailRepository emails, EmailClassifier classifier, AiHealth aiHealth, AccountViews accounts,
			SyncRunner syncRunner, SyncService syncService, Clock clock, ObjectMapper json) {
		this.apps = apps;
		this.actions = actions;
		this.reviews = reviews;
		this.emails = emails;
		this.classifier = classifier;
		this.aiHealth = aiHealth;
		this.accounts = accounts;
		this.syncRunner = syncRunner;
		this.syncService = syncService;
		this.clock = clock;
		this.json = json;
	}

	@GetMapping("/api/dashboard")
	public Dashboard dashboard() {
		Instant now = clock.instant();
		List<Application> all = apps.findAll();
		Map<Long, Application> byId = new HashMap<>();
		all.forEach(a -> byId.put(a.id(), a));
		List<ApplicationAction> open = actions.findOpen();

		boolean aiConfigured = classifier.isConfigured();
		AiHealth.Snapshot health = aiHealth.snapshot();
		Ai ai = new Ai(aiConfigured, health.lastError(), health.lastErrorAt(), health.lastSuccessAt(),
				emails.countWaiting());
		return new Dashboard(accounts.list(), aiConfigured, ai,
				syncRunner.isSyncing() || syncService.isRunning(),
				syncService.progress().map(p -> new Progress(p.downloaded(), p.checked())).orElse(null), chart(all),
				needsAction(open, byId, now), pleaseCheck(byId), board(all, open, now));
	}

	private List<ChartSlice> chart(List<Application> all) {
		Map<Stage, Integer> counts = new EnumMap<>(Stage.class);
		all.forEach(a -> counts.merge(a.stage(), 1, Integer::sum));
		List<ChartSlice> chart = new ArrayList<>();
		for (Stage s : CHART_STAGES) {
			chart.add(new ChartSlice(s.name(), counts.getOrDefault(s, 0)));
		}
		chart.add(new ChartSlice("CLOSED", counts.getOrDefault(Stage.REJECTED, 0) + counts.getOrDefault(Stage.WITHDRAWN, 0)));
		return chart;
	}

	private List<NeedsAction> needsAction(List<ApplicationAction> open, Map<Long, Application> byId, Instant now) {
		return open.stream().filter(a -> byId.containsKey(a.applicationId()))
				.sorted(Comparator.comparing(ApplicationAction::dueAt,
						Comparator.nullsLast(Comparator.<OffsetDateTime>naturalOrder())))
				.map(a -> {
					Application app = byId.get(a.applicationId());
					return new NeedsAction(a.id(), app.id(), app.company(), app.role(), a.description(), a.dueAt(),
							urgency(a.dueAt(), now));
				}).toList();
	}

	private List<PleaseCheck> pleaseCheck(Map<Long, Application> byId) {
		List<PleaseCheck> out = new ArrayList<>();
		for (ReviewItem item : reviews.findOpen()) {
			EmailRecord email = item.emailId() == null ? null : emails.find(item.emailId()).orElse(null);
			Application app = item.applicationId() == null ? null : byId.get(item.applicationId());
			JsonNode suggestion = suggestion(item.suggestionJson());
			out.add(new PleaseCheck(item.id(), item.reason().name(), item.applicationId(),
					app == null ? null : app.company(), app == null ? null : app.stage().name(),
					candidateIds(suggestion), email == null ? null : email.subject(),
					email == null ? null : email.from(), email == null ? null : email.receivedAt(),
					email == null ? null : email.webLink(), suggestion));
		}
		return out;
	}

	private static List<Long> candidateIds(JsonNode suggestion) {
		JsonNode ids = suggestion == null ? null : suggestion.get("candidateIds");
		if (ids == null || !ids.isArray()) {
			return List.of();
		}
		List<Long> out = new ArrayList<>();
		ids.forEach(n -> {
			if (n.isIntegralNumber()) {
				out.add(n.longValue());
			}
		});
		return out;
	}

	private JsonNode suggestion(String raw) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		try {
			return json.readTree(raw);
		}
		catch (RuntimeException e) {
			return null;
		}
	}

	private Map<String, List<Card>> board(List<Application> all, List<ApplicationAction> open, Instant now) {
		Map<Long, Urgency> worst = new HashMap<>();
		for (ApplicationAction a : open) {
			worst.merge(a.applicationId(), urgency(a.dueAt(), now), (x, y) -> x.compareTo(y) <= 0 ? x : y);
		}
		Map<String, List<Card>> board = new LinkedHashMap<>();
		BOARD_COLUMNS.forEach(c -> board.put(c, new ArrayList<>()));
		for (Application a : all) {
			if (!a.stage().onBoard()) {
				continue;
			}
			String column = a.stage().isClosed() ? "OUTCOME" : a.stage().name();
			Urgency u = worst.get(a.id());
			board.get(column).add(new Card(a.id(), a.company(), a.role(), a.stage().name(), a.interviewRounds(),
					u != null, u));
		}
		return board;
	}

	/** OVERDUE when past, URGENT within a day, SOON within three days, otherwise (or undated) LATER. */
	static Urgency urgency(OffsetDateTime dueAt, Instant now) {
		if (dueAt == null) {
			return Urgency.LATER;
		}
		Instant due = dueAt.toInstant();
		if (due.isBefore(now)) {
			return Urgency.OVERDUE;
		}
		Duration left = Duration.between(now, due);
		if (left.compareTo(Duration.ofHours(24)) <= 0) {
			return Urgency.URGENT;
		}
		if (left.compareTo(Duration.ofHours(72)) <= 0) {
			return Urgency.SOON;
		}
		return Urgency.LATER;
	}
}
