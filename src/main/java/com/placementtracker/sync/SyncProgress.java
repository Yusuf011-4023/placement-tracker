package com.placementtracker.sync;

/** Live counters of the running sync pass: messages downloaded and emails classified so far this run. */
public record SyncProgress(int downloaded, int checked) {
}
