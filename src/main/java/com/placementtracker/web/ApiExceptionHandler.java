package com.placementtracker.web;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import com.placementtracker.application.NotFoundException;

@RestControllerAdvice
public class ApiExceptionHandler {

	@ExceptionHandler(NotFoundException.class)
	ResponseEntity<Map<String, String>> notFound(NotFoundException e) {
		return body(HttpStatus.NOT_FOUND, e.getMessage());
	}

	@ExceptionHandler(IllegalArgumentException.class)
	ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
		return body(HttpStatus.BAD_REQUEST, e.getMessage());
	}

	@ExceptionHandler({ HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class })
	ResponseEntity<Map<String, String>> unreadable(Exception e) {
		return body(HttpStatus.BAD_REQUEST, "Malformed request");
	}

	@ExceptionHandler(IllegalStateException.class)
	ResponseEntity<Map<String, String>> conflict(IllegalStateException e) {
		return body(HttpStatus.CONFLICT, e.getMessage());
	}

	private static ResponseEntity<Map<String, String>> body(HttpStatus status, String message) {
		return ResponseEntity.status(status).body(Map.of("error", message == null ? status.getReasonPhrase() : message));
	}
}
