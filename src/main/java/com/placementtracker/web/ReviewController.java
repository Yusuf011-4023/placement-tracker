package com.placementtracker.web;

import java.util.Map;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.placementtracker.classify.Classification;
import com.placementtracker.review.ReviewService;
import com.placementtracker.web.dto.Dtos.AttachRequest;

@RestController
public class ReviewController {

	private static final Map<String, Object> OK = Map.of("ok", true);

	private final ReviewService reviews;
	private final SyncRunner syncRunner;

	public ReviewController(ReviewService reviews, SyncRunner syncRunner) {
		this.reviews = reviews;
		this.syncRunner = syncRunner;
	}

	/** "Try again" on an AI_FAILED item: back to PENDING with fresh attempts, then a sync picks it up. */
	@PostMapping("/api/review/{id}/retry")
	public Map<String, Object> retry(@PathVariable long id) {
		reviews.retry(id);
		syncRunner.trigger();
		return OK;
	}

	@PostMapping("/api/review/{id}/confirm")
	public Map<String, Object> confirm(@PathVariable long id, @RequestBody(required = false) Classification edited) {
		reviews.confirm(id, edited);
		return OK;
	}

	@PostMapping("/api/review/{id}/dismiss")
	public Map<String, Object> dismiss(@PathVariable long id) {
		reviews.dismissNotPlacement(id);
		return OK;
	}

	@PostMapping("/api/review/{id}/attach")
	public Map<String, Object> attach(@PathVariable long id, @RequestBody AttachRequest body) {
		if (body.applicationId() == null) {
			throw new IllegalArgumentException("applicationId is required");
		}
		reviews.attachTo(id, body.applicationId());
		return OK;
	}
}
