package com.placementtracker.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DataDirTest {

	private static TrackerProperties props(Path dataDir) {
		return new TrackerProperties(dataDir, null, null, null);
	}

	@Test
	void defaultsToLocalAppData() {
		String localAppData = System.getenv("LOCALAPPDATA");
		Path expectedBase = localAppData != null ? Path.of(localAppData)
				: Path.of(System.getProperty("user.home"), "AppData", "Local");

		DataDir dir = new DataDir(props(null));

		assertThat(dir.root()).isEqualTo(expectedBase.resolve("PlacementTracker"));
		assertThat(dir.root().getFileName().toString()).isEqualTo("PlacementTracker");
	}

	@Test
	void overrideIsUsedAndCreated(@TempDir Path tmp) {
		Path target = tmp.resolve("x");

		DataDir dir = new DataDir(props(target));

		assertThat(dir.root()).isEqualTo(target);
		assertThat(Files.isDirectory(target)).isTrue();
		assertThat(dir.resolve("tracker.db")).isEqualTo(target.resolve("tracker.db"));
	}
}
