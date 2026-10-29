package com.placementtracker.classify;

import com.placementtracker.mail.MailMessage;

public interface EmailClassifier {

	/** False when no API key is configured; classify then fails fast. */
	boolean isConfigured();

	Classification classify(MailMessage m) throws ClassificationException;
}
