package com.placementtracker.notify;

/** Tells the owner something. Implementations must not throw. */
public interface Notifier {

	void notify(String title, String message);
}
