package com.placementtracker.mail;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

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

	/** Used to retry emails that failed earlier. */
	Optional<MailMessage> fetchOne(String messageId) throws MailAuthException, MailSourceException;
}
