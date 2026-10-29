package com.placementtracker.review;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.placementtracker.application.Application;
import com.placementtracker.application.ApplicationRepository;
import com.placementtracker.application.StageRules;
import com.placementtracker.application.TransitionDecision;
import com.placementtracker.classify.Classification;
import com.placementtracker.mail.EmailRecord;
import com.placementtracker.mail.EmailRepository;
import com.placementtracker.mail.EmailStatus;
import com.placementtracker.processing.EmailProcessor;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** One-click resolutions for the "Please check" queue (spec 5.6). Every successful action resolves the item. */
@Service
public class ReviewService {

	private final ReviewRepository reviews;
	private final EmailRepository emails;
	private final ApplicationRepository apps;
	private final EmailProcessor processor;
	private final ObjectMapper json;

	public ReviewService(ReviewRepository reviews, EmailRepository emails, ApplicationRepository apps,
			EmailProcessor processor, ObjectMapper json) {
		this.reviews = reviews;
		this.emails = emails;
		this.apps = apps;
		this.processor = processor;
		this.json = json;
	}

	/** Applies the suggestion (or the owner's edited version) to the matched or a new application, ungated. */
	@Transactional
	public void confirm(long reviewId, Classification edited) {
		ReviewItem item = openItem(reviewId);
		Classification c = edited != null ? edited : suggestionOf(item);
		if (c == null) {
			throw new IllegalStateException("nothing to confirm; edit first");
		}
		processor.applyConfirmed(emailOf(item), c, item.applicationId());
		reviews.resolve(reviewId);
	}

	@Transactional
	public void dismissNotPlacement(long reviewId) {
		ReviewItem item = openItem(reviewId);
		emails.setStatus(emailOf(item).id(), EmailStatus.NOT_PLACEMENT);
		reviews.resolve(reviewId);
	}

	/**
	 * Links the email to the chosen application and applies the suggestion through StageRules (unlocked). A
	 * regression or closed-application change is not applied; it leaves a new open item for an explicit confirm.
	 */
	@Transactional
	public void attachTo(long reviewId, long applicationId) {
		ReviewItem item = openItem(reviewId);
		Classification c = suggestionOf(item);
		if (c == null || c.stage() == null) {
			throw new IllegalStateException("nothing to attach; edit first");
		}
		EmailRecord email = emailOf(item);
		Application app = apps.find(applicationId)
				.orElseThrow(() -> new IllegalArgumentException("No such application: " + applicationId));
		TransitionDecision d = StageRules.decide(app.stage(), c.stage(), false);
		if (d.kind() == TransitionDecision.Kind.REVIEW) {
			emails.linkApplication(email.id(), applicationId);
			Map<String, Object> suggestion = new LinkedHashMap<>();
			suggestion.put("classification", c);
			suggestion.put("applicationId", applicationId);
			reviews.open(email.id(), applicationId, d.reason(), json.writeValueAsString(suggestion));
		}
		else {
			processor.applyConfirmed(email, c, applicationId);
		}
		reviews.resolve(reviewId);
	}

	private ReviewItem openItem(long id) {
		ReviewItem item = reviews.find(id)
				.orElseThrow(() -> new IllegalArgumentException("No such review item: " + id));
		if (!"OPEN".equals(item.status())) {
			throw new IllegalArgumentException("Review item already resolved: " + id);
		}
		return item;
	}

	private EmailRecord emailOf(ReviewItem item) {
		if (item.emailId() == null) {
			throw new IllegalStateException("review item has no email");
		}
		return emails.find(item.emailId())
				.orElseThrow(() -> new IllegalStateException("email missing for review item " + item.id()));
	}

	/** The stored classification, or null when the item has no usable suggestion (e.g. AI_FAILED). */
	private Classification suggestionOf(ReviewItem item) {
		if (item.reason() == ReviewReason.AI_FAILED || item.suggestionJson() == null
				|| item.suggestionJson().isBlank()) {
			return null;
		}
		JsonNode node = json.readTree(item.suggestionJson()).get("classification");
		if (node == null || node.isNull()) {
			return null;
		}
		return json.treeToValue(node, Classification.class);
	}
}
