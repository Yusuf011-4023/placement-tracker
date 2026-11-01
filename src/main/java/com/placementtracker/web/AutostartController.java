package com.placementtracker.web;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.placementtracker.launch.AutostartService;

/** Reads and toggles start-at-login. */
@RestController
public class AutostartController {

	public record Toggle(boolean enabled) {
	}

	private final AutostartService autostart;

	public AutostartController(AutostartService autostart) {
		this.autostart = autostart;
	}

	@GetMapping("/api/autostart")
	public Map<String, Boolean> get() {
		return state();
	}

	@PutMapping("/api/autostart")
	public Map<String, Boolean> put(@RequestBody Toggle body) {
		autostart.setEnabled(body.enabled()); // IllegalStateException -> 409 when not installed
		return state();
	}

	private Map<String, Boolean> state() {
		boolean available = autostart.available();
		return Map.of("available", available, "enabled", available && autostart.enabled());
	}
}
