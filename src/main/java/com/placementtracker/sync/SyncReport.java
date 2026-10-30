package com.placementtracker.sync;

import java.util.List;
import java.util.Map;

import com.placementtracker.mail.MailAccount;

/** Outcome of one sync pass. {@code aiConfigured} is false when no API key is set, so mail waits as PENDING. */
public record SyncReport(int fetched, int classified, int created, int updated, int newReviewItems,
		List<Long> newActionIds, Map<MailAccount, SyncStatus> statuses, boolean aiConfigured) {
}
