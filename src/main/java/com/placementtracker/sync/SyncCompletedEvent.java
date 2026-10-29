package com.placementtracker.sync;

/** Published after each scheduled sync pass. */
public record SyncCompletedEvent(SyncReport report) {
}
