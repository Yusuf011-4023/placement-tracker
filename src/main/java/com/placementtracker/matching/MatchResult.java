package com.placementtracker.matching;

import java.util.List;

/** Outcome of matching one classified email against the stored applications. */
public sealed interface MatchResult permits MatchResult.Existing, MatchResult.New, MatchResult.Ambiguous {

	/** The email belongs to this existing application. */
	record Existing(long applicationId) implements MatchResult {
	}

	/** No application for this company exists; a new one should be created. */
	record New() implements MatchResult {
	}

	/** Cannot decide safely; the email goes to the "Please check" queue with these candidate ids. */
	record Ambiguous(List<Long> candidateIds) implements MatchResult {

		public Ambiguous {
			candidateIds = List.copyOf(candidateIds);
		}
	}
}
