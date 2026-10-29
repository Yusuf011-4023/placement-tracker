package com.placementtracker.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DataDirTest {

	private static TrackerProperties props(Path dataDir) {
		return new TrackerProperties(dataDir, null, null);
	}

	@Test
	void defaultsToLocalAppData() {
		// Checks the default path only; never constructs a DataDir on it, so the owner's real dir is not created.
		String localAppData = System.getenv("LOCALAPPDATA");
		Path expectedBase = localAppData != null && !localAppData.isBlank() ? Path.of(localAppData)
				: Path.of(System.getProperty("user.home"), "AppData", "Local");

		Path root = DataDir.defaultRoot();

		assertThat(root.getFileName().toString()).isEqualTo("PlacementTracker");
		assertThat(root.getParent()).isEqualTo(expectedBase);
	}

	@Test
	void overrideIsUsedAndCreated(@TempDir Path tmp) {
		Path target = tmp.resolve("x");

		DataDir dir = new DataDir(props(target));

		assertThat(dir.root()).isEqualTo(target);
		assertThat(Files.isDirectory(target)).isTrue();
		assertThat(dir.resolve("tracker.db")).isEqualTo(target.resolve("tracker.db"));
	}

	@Test
	void testContextsNeverUseTheRealDataDir() throws Exception {
		java.util.Properties p = new java.util.Properties();
		try (var in = DataDirTest.class.getResourceAsStream("/application.properties")) {
			p.load(in);
		}
		assertThat(p.getProperty("tracker.data-dir")).startsWith("${java.io.tmpdir}/");
	}
}
