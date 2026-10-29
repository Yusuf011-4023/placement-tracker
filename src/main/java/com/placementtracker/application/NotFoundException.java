package com.placementtracker.application;

/** A requested entity does not exist; the web layer maps it to 404. */
public class NotFoundException extends RuntimeException {

	public NotFoundException(String message) {
		super(message);
	}
}
