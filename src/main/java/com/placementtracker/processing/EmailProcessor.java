package com.placementtracker.processing;

import java.time.ZoneOffset;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.placementtracker.application.ActionRepository;
import com.placementtracker.application.Application;
import com.placementtracker.application.ApplicationRepository;
import com.placementtracker.application.HistoryRepository;
import com.placementtracker.application.Stage;
import com.placementtracker.application.StageMover;
import com.placementtracker.application.StageRules;
import com.placementtracker.application.TransitionDecision;
import com.placementtracker.classify.Classification;
import com.placementtracker.classify.Relevance;
import com.placementtracker.classify.SuggestedAction;
import com.placementtracker.config.TrackerProperties;
import com.placementtracker.mail.EmailRecord;
import com.placementtracker.mail.EmailRepository;
import com.placementtracker.mail.EmailStatus;
import com.placementtracker.mail.MailMessage;
import com.placementtracker.matching.ApplicationMatcher;
import com.placementtracker.matching.CompanyNames;
import com.placementtracker.matching.MatchResult;
import com.placementtracker.review.ReviewReason;
import com.placementtracker.review.ReviewRepository;

import tools.jackson.databind.ObjectMapper;

/** Applies one email classification to the database (spec 5.4 to 5.7). */
@Service
public class EmailProcessor {

	private static final String SOURCE_EMAIL = "EMAIL";
	private static final String SOURCE_USER = "USER";

	private final ApplicationRepository apps;
	private final ActionRepository actions;
	private final HistoryRepository history;
	private final EmailRepository emails;
	private final ReviewRepository reviews;
	private final ApplicationMatcher matcher;
	private final StageMover mover;
	private final TrackerProperties props;
	private final ObjectMapper json;

	private final List<Long> newActionIds = new ArrayList<>();

	public EmailProcessor(ApplicationRepository apps, ActionRepository actions, HistoryRepository history,
			EmailRepository emails, ReviewRepository reviews, ApplicationMatcher matcher, StageMover mover, TrackerProperties props,
			ObjectMapper json) {
		this.apps = apps;
		this.actions = actions;
		this.history = history;
		this.emails = emails;
		this.reviews = reviews;
		this.matcher = matcher;
		this.mover = mover;
		this.props = props;
		this.json = json;
	}

	@Transactional
	public ProcessOutcome process(EmailRecord email, MailMessage m, Classification c) {
		if (c.relevance() == Relevance.NO) {
			emails.setStatus(email.id(), EmailStatus.NOT_PLACEMENT);
			return ProcessOutcome.NOT_PLACEMENT;
		}
		MatchResult match = matcher.match(c, m);
		Long knownApp = match instanceof MatchResult.Existing e ? e.applicationId() : null;

		if (c.relevance() == Relevance.UNSURE || c.stage() == null
				|| c.confidence() < props.ai().confidenceThreshold()) {
			return review(email, knownApp, ReviewReason.LOW_CONFIDENCE, c, null);
		}
		return switch (match) {
			case MatchResult.Ambiguous a -> review(email, null, ReviewReason.AMBIGUOUS_MATCH, c, a.candidateIds());
			case MatchResult.New n -> create(email, m, c);
			case MatchResult.Existing e -> update(email, e.applicationId(), c);
		};
	}

	/** Returns the ids of actions created since the last call, and forgets them. */
	public synchronized List<Long> newActionIds() {
		List<Long> copy = List.copyOf(newActionIds);
		newActionIds.clear();
		return copy;
	}

	/**
	 * Applies a classification the owner has decided on, with no confidence check and no stage locks. A null
	 * applicationId creates a new application; otherwise that application is moved to the classification's stage.
	 * History is recorded with source USER. A locked stage stays locked, now holding the new value.
	 */
	@Transactional
	public void applyConfirmed(EmailRecord email, Classification c, Long applicationId) {
		if (c == null || c.stage() == null) {
			throw new IllegalArgumentException("A stage is required to apply this email");
		}
		if (applicationId == null) {
			if (c.company() == null || c.company().isBlank()) {
				throw new IllegalArgumentException("A company is required to create an application");
			}
			createApplication(email, email.receivedAt(), c, SOURCE_USER);
		}
		else {
			Application app = apps.find(applicationId)
					.orElseThrow(() -> new IllegalArgumentException("No such application: " + applicationId));
			emails.linkApplication(email.id(), applicationId);
			moveToStage(app, c, email.id(), SOURCE_USER);
		}
		emails.setStatus(email.id(), EmailStatus.PROCESSED);
	}

	private ProcessOutcome create(EmailRecord email, MailMessage m, Classification c) {
		createApplication(email, m.receivedAt(), c, SOURCE_EMAIL);
		emails.setStatus(email.id(), EmailStatus.PROCESSED);
		return ProcessOutcome.CREATED;
	}

	private void createApplication(EmailRecord email, java.time.Instant receivedAt, Classification c, String source) {
		Stage stage = c.stage();
		var appliedOn = stage == Stage.NOT_SUBMITTED || receivedAt == null ? null
				: receivedAt.atOffset(ZoneOffset.UTC).toLocalDate();
		long id = apps.insert(c.company().trim(), CompanyNames.key(c.company()), blankToNull(c.role()), stage,
				appliedOn, null);
		history.record(id, null, stage, source, email.id());
		if (stage == Stage.INTERVIEW) {
			apps.incrementInterviewRounds(id);
		}
		emails.linkApplication(email.id(), id);
		if (!stage.isClosed()) {
			addActions(id, c, stage, email.id(), c.company().trim());
		}
	}

	private ProcessOutcome update(EmailRecord email, long appId, Classification c) {
		Application app = apps.find(appId).orElseThrow();
		emails.linkApplication(email.id(), appId);
		if (isStale(email, appId)) {
			// History catching up: an email older than the one that set the current stage never moves it (and
			// never opens a review); it only contributes to-dos for the stage the application is in now.
			if (c.stage() == app.stage() && !app.stage().isClosed()) {
				moveToStage(app, c, email.id(), SOURCE_EMAIL);
			}
			emails.setStatus(email.id(), EmailStatus.PROCESSED);
			return ProcessOutcome.UPDATED;
		}
		TransitionDecision d = StageRules.decide(app.stage(), c.stage(), app.lockedFields().contains("stage"));
		if (d.kind() == TransitionDecision.Kind.REVIEW) {
			return review(email, appId, d.reason(), c, null);
		}
		moveToStage(app, c, email.id(), SOURCE_EMAIL);
		emails.setStatus(email.id(), EmailStatus.PROCESSED);
		return ProcessOutcome.UPDATED;
	}

	/** True when the email predates the email behind the application's latest EMAIL-sourced stage change. */
	private boolean isStale(EmailRecord email, long appId) {
		if (email.receivedAt() == null) {
			return false;
		}
		return history.latestEmailChangeReceivedAt(appId).map(latest -> email.receivedAt().isBefore(latest))
				.orElse(false);
	}

	/** Moves an existing application to c.stage() (or just adds actions if unchanged), without any gating. */
	private void moveToStage(Application app, Classification c, long emailId, String source) {
		long appId = app.id();
		Stage proposed = c.stage();
		if (mover.move(app, proposed, source, emailId, false)) {
			if (!proposed.isClosed()) {
				addActions(appId, c, proposed, emailId, app.company());
			}
		}
		else if (!proposed.isClosed()) {
			int added = addActions(appId, c, proposed, emailId, app.company());
			if (added > 0 && proposed == Stage.INTERVIEW) {
				apps.incrementInterviewRounds(appId);
			}
		}
	}

	/**
	 * Adds each suggested action not already open; returns how many were new. For NOT_SUBMITTED the to-do is always
	 * the standard "Finish your <company> application", taking only the earliest deadline from the model's actions.
	 */
	private int addActions(long appId, Classification c, Stage stage, long emailId, String company) {
		List<SuggestedAction> suggested = c.actions() == null ? List.of() : c.actions();
		if (stage == Stage.NOT_SUBMITTED) {
			OffsetDateTime earliest = suggested.stream().filter(java.util.Objects::nonNull).map(c::dueAtOf)
					.filter(java.util.Objects::nonNull).min(java.util.Comparator.naturalOrder()).orElse(null);
			return addAction(appId, "Finish your " + company + " application", earliest, stage, emailId);
		}
		int added = 0;
		for (SuggestedAction a : suggested) {
			if (a == null || a.description() == null || a.description().isBlank()) {
				continue;
			}
			added += addAction(appId, a.description().trim(), c.dueAtOf(a), stage, emailId);
		}
		return added;
	}

	private int addAction(long appId, String description, OffsetDateTime due, Stage stage, long emailId) {
		if (actions.existsOpen(appId, description, due)) {
			return 0;
		}
		long id = actions.insert(appId, description, due, stage, emailId);
		synchronized (this) {
			newActionIds.add(id);
		}
		return 1;
	}

	private ProcessOutcome review(EmailRecord email, Long appId, ReviewReason reason, Classification c,
			List<Long> candidateIds) {
		if (appId != null) {
			emails.linkApplication(email.id(), appId);
		}
		Map<String, Object> suggestion = new LinkedHashMap<>();
		suggestion.put("classification", c);
		if (candidateIds != null) {
			suggestion.put("candidateIds", candidateIds);
		}
		if (appId != null) {
			suggestion.put("applicationId", appId);
		}
		reviews.open(email.id(), appId, reason, json.writeValueAsString(suggestion));
		emails.setStatus(email.id(), EmailStatus.REVIEW);
		return ProcessOutcome.REVIEW;
	}

	private static String blankToNull(String s) {
		return s == null || s.isBlank() ? null : s.trim();
	}
}
