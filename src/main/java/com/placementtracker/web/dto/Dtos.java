package com.placementtracker.web.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import tools.jackson.databind.JsonNode;

/** JSON shapes of the local API. */
public final class Dtos {

	private Dtos() {
	}

	public enum Urgency { OVERDUE, URGENT, SOON, LATER }

	public record Account(String account, boolean connected, String status, Instant lastSuccessAt, String error) {
	}

	public record ChartSlice(String stage, int count) {
	}

	public record NeedsAction(long actionId, long applicationId, String company, String role, String description,
			OffsetDateTime dueAt, Urgency urgency) {
	}

	public record PleaseCheck(long reviewId, String reason, Long applicationId, String subject, String from,
			Instant receivedAt, JsonNode suggestion) {
	}

	public record Card(long applicationId, String company, String role, String stage, int interviewRounds,
			boolean hasOpenAction, Urgency urgency) {
	}

	public record Dashboard(List<Account> accounts, boolean aiConfigured, boolean syncing, List<ChartSlice> chart,
			List<NeedsAction> needsAction, List<PleaseCheck> pleaseCheck, Map<String, List<Card>> board) {
	}

	public record ActionView(long id, String description, OffsetDateTime dueAt, String status, String stage) {
	}

	public record EmailRef(String subject, String webLink) {
	}

	public record HistoryEntry(String fromStage, String toStage, Instant changedAt, String source, EmailRef email) {
	}

	public record ApplicationDetail(long id, String company, String role, String stage, int interviewRounds,
			LocalDate appliedOn, String notes, List<String> lockedFields, List<ActionView> actions,
			List<HistoryEntry> history) {
	}

	public record ApplicationSummary(long id, String company, String role, String stage) {
	}

	public record NewApplication(String company, String role, String stage, LocalDate appliedOn, String notes) {
	}

	public record NewAction(String description, OffsetDateTime dueAt) {
	}

	public record MergeRequest(Long sourceId) {
	}

	public record AttachRequest(Long applicationId) {
	}
}
