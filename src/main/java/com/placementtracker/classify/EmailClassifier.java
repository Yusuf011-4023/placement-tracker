package com.placementtracker.classify;

import com.placementtracker.mail.MailMessage;

public interface EmailClassifier {

	Classification classify(MailMessage m) throws ClassificationException;
}
