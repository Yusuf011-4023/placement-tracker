package com.placementtracker.sync;

import java.time.Instant;

import com.placementtracker.mail.MailAccount;

public record SyncState(MailAccount account, Instant highWater, Instant lastSuccessAt, SyncStatus status,
		String lastError) {
}
