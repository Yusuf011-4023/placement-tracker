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

	private final ApplicationRepository apps;
	private final ActionRepository actions;
	private final HistoryRepository history;
	private final EmailRepository emails;
	private final ReviewRepository reviews;
	private final ApplicationMatcher matcher;
	private final TrackerProperties props;
	private final ObjectMapper json;

	private final List<Long> newActionIds = new ArrayList<>();

	public EmailProcessor(ApplicationRepository apps, ActionRepository actions, HistoryRepository history,
			EmailRepository emails, ReviewRepository reviews, ApplicationMatcher matcher, TrackerProperties props,
			ObjectMapper json) {
		this.apps = apps;
		this.actions = actions;
		this.history = history;
		this.emails = emails;
		this.reviews = reviews;
		this.matcher = matcher;
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

	private ProcessOutcome create(EmailRecord email, MailMessage m, Classification c) {
		Stage stage = c.stage();
		var appliedOn = stage == Stage.NOT_SUBMITTED || m.receivedAt() == null ? null
				: m.receivedAt().atOffset(ZoneOffset.UTC).toLocalDate();
		long id = apps.insert(c.company().trim(), CompanyNames.key(c.company()), blankToNull(c.role()), stage,
				appliedOn, null);
		history.record(id, null, stage, SOURCE_EMAIL, email.id());
		if (stage == Stage.INTERVIEW) {
			apps.incrementInterviewRounds(id);
		}
		emails.linkApplication(email.id(), id);
		if (!stage.isClosed()) {
			addActions(id, c, stage, email.id(), c.company().trim());
		}
		emails.setStatus(email.id(), EmailStatus.PROCESSED);
		return ProcessOutcome.CREATED;
	}

	private ProcessOutcome update(EmailRecord email, long appId, Classification c) {
		Application app = apps.find(appId).orElseThrow();
		emails.linkApplication(email.id(), appId);
		Stage proposed = c.stage();
		TransitionDecision d = StageRules.decide(app.stage(), proposed, app.lockedFields().contains("stage"));
		switch (d.kind()) {
			case REVIEW -> {
				return review(email, appId, d.reason(), c, null);
			}
			case APPLY -> {
				actions.closeOpenForStage(appId, app.stage());
				apps.updateStage(appId, proposed);
				history.record(appId, app.stage(), proposed, SOURCE_EMAIL, email.id());
				if (proposed == Stage.INTERVIEW) {
					apps.incrementInterviewRounds(appId);
				}
				if (proposed.isClosed()) {
					actions.closeAllOpen(appId);
				}
				else {
					addActions(appId, c, proposed, email.id(), app.company());
				}
			}
			case NO_CHANGE -> {
				if (!proposed.isClosed()) {
					int added = addActions(appId, c, proposed, email.id(), app.company());
					if (added > 0 && proposed == Stage.INTERVIEW) {
						apps.incrementInterviewRounds(appId);
					}
				}
			}
		}
		emails.setStatus(email.id(), EmailStatus.PROCESSED);
		return ProcessOutcome.UPDATED;
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
