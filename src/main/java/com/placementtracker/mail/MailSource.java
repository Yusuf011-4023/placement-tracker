package com.placementtracker.mail;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface MailSource {

	MailAccount account();

	boolean isConnected();

	void connect() throws MailAuthException;

	/** Results are ordered by {@code receivedAt} ascending. */
	List<MailMessage> fetchSince(Instant since) throws MailAuthException, MailSourceException;

	/** Used to retry emails that failed earlier. */
	Optional<MailMessage> fetchOne(String messageId) throws MailAuthException, MailSourceException;
}
