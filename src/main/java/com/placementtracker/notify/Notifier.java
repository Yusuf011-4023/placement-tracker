package com.placementtracker.notify;

/** Tells the owner something. Implementations must not throw. */
public interface Notifier {

	/** Returns true if the notification was shown (or logged); false if it could not be shown. */
	boolean notify(String title, String message);
}
