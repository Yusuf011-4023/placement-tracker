package com.placementtracker.config;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.springframework.stereotype.Component;

/** The data directory of the app; created on construction. */
@Component
public class DataDir {

	private final Path root;

	public DataDir(TrackerProperties properties) {
		this.root = properties.dataDir() != null ? properties.dataDir() : defaultRoot();
		try {
			Files.createDirectories(root);
		}
		catch (IOException e) {
			throw new UncheckedIOException("Cannot create data directory " + root, e);
		}
	}

	private static Path defaultRoot() {
		String base = System.getenv("LOCALAPPDATA");
		Path localAppData = base != null && !base.isBlank() ? Path.of(base)
				: Path.of(System.getProperty("user.home"), "AppData", "Local");
		return localAppData.resolve("PlacementTracker");
	}

	public Path root() {
		return root;
	}

	public Path resolve(String name) {
		return root.resolve(name);
	}
}
