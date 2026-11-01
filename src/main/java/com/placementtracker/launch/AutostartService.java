package com.placementtracker.launch;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import com.placementtracker.config.DataDir;

/** Start-at-login for the installed app, via the per-user Run registry key. */
@Service
public class AutostartService {

	static final String VALUE_NAME = "PlacementTracker";
	static final String MARKER = "autostart-initialised";

	private static final Logger log = LoggerFactory.getLogger(AutostartService.class);

	private final RunKey runKey;
	private final Supplier<Optional<String>> exe;
	private final DataDir dataDir;

	@Autowired
	public AutostartService(RunKey runKey, DataDir dataDir) {
		this(runKey, AutostartService::installedExe, dataDir);
	}

	public AutostartService(RunKey runKey, Supplier<Optional<String>> exe, DataDir dataDir) {
		this.runKey = runKey;
		this.exe = exe;
		this.dataDir = dataDir;
	}

	/** The installed exe path; set by the jpackage launcher, absent when run from a jar or an IDE. */
	static Optional<String> installedExe() {
		String path = System.getProperty("jpackage.app-path");
		return path == null || path.isBlank() ? Optional.empty() : Optional.of(path);
	}

	public boolean available() {
		return exe.get().isPresent();
	}

	public boolean enabled() {
		return runKey.read(VALUE_NAME).isPresent();
	}

	public void setEnabled(boolean on) {
		String path = exe.get().orElseThrow(
				() -> new IllegalStateException("Auto-start is only available in the installed app"));
		if (on) {
			runKey.write(VALUE_NAME, command(path));
		}
		else {
			runKey.delete(VALUE_NAME);
		}
	}

	private static String command(String exePath) {
		return "\"" + exePath + "\" --autostart";
	}

	@EventListener(ApplicationReadyEvent.class)
	public void onReady() {
		try {
			Optional<String> path = exe.get();
			if (path.isEmpty()) {
				return;
			}
			Path marker = dataDir.resolve(MARKER);
			if (!Files.exists(marker)) {
				runKey.write(VALUE_NAME, command(path.get()));
				createMarker(marker);
				return;
			}
			Optional<String> stored = runKey.read(VALUE_NAME);
			if (stored.isPresent() && !stored.get().equals(command(path.get()))) {
				runKey.write(VALUE_NAME, command(path.get()));
			}
		}
		catch (RuntimeException e) {
			log.warn("Could not set up auto-start: {}", e.getMessage());
		}
	}

	private static void createMarker(Path marker) {
		try {
			Files.writeString(marker, "");
		}
		catch (IOException e) {
			log.warn("Could not write {}: {}", marker, e.getMessage());
		}
	}
}
