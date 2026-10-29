package com.placementtracker.review;

public record ReviewItem(long id, Long emailId, Long applicationId, ReviewReason reason, String suggestionJson,
		String status) {
}
