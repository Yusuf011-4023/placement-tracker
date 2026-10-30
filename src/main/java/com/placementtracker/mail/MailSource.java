package com.placementtracker.mail;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

public interface MailSource {

	MailAccount account();

	boolean isConnected();

	void connect() throws MailAuthException;

	/** What the owner must do before {@link #connect()} can work (e.g. a missing file), or empty when ready. */
	default Optional<String> setupProblem() {
		return Optional.empty();
	}

	/** Results are ordered by {@code receivedAt} ascending. */
	List<MailMessage> fetchSince(Instant since) throws MailAuthException, MailSourceException;

	/**
	 * Like {@link #fetchSince(Instant)}, but a source that can may skip ids for which {@code alreadyStored} is true
	 * (they are not fetched and do not count toward {@link #batchLimit()}).
	 */
	default List<MailMessage> fetchSince(Instant since, Predicate<String> alreadyStored)
			throws MailAuthException, MailSourceException {
		return fetchSince(since);
	}

	/**
	 * The most messages one {@code fetchSince} call returns. A call that returns exactly this many probably has more
	 * waiting, so the caller fetches again from the new high-water mark.
	 */
	default int batchLimit() {
		return Integer.MAX_VALUE;
	}

	/** Used to retry emails that failed earlier. */
	Optional<MailMessage> fetchOne(String messageId) throws MailAuthException, MailSourceException;
}
