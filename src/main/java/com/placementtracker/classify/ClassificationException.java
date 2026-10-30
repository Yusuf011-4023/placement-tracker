package com.placementtracker.classify;

/** Classification failed; {@link #isRetryable()} says whether trying again later may succeed. */
public class ClassificationException extends Exception {

	private final boolean retryable;

	public ClassificationException(String message, boolean retryable) {
		super(message);
		this.retryable = retryable;
	}

	public ClassificationException(String message, Throwable cause, boolean retryable) {
		super(message, cause);
		this.retryable = retryable;
	}

	public boolean isRetryable() {
		return retryable;
	}
}
