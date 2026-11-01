package com.placementtracker.launch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.placementtracker.config.DataDir;
import com.placementtracker.config.TrackerProperties;

class AutostartServiceTest {

	static final String EXE = "C:\\Apps\\Placement Tracker\\Placement Tracker.exe";
	static final String COMMAND = "\"" + EXE + "\" --autostart";

	@TempDir Path dir;
	final Map<String, String> registry = new HashMap<>();
	final RunKey runKey = new RunKey() {
		public Optional<String> read(String name) { return Optional.ofNullable(registry.get(name)); }
		public void write(String name, String command) { registry.put(name, command); }
		public void delete(String name) { registry.remove(name); }
	};
	DataDir dataDir;

	@BeforeEach
	void setUp() {
		dataDir = new DataDir(new TrackerProperties(dir, null, null, null));
	}

	AutostartService service(Optional<String> exe) {
		return new AutostartService(runKey, () -> exe, dataDir);
	}

	@Test
	void firstLaunchEnablesAndWritesMarker() {
		AutostartService s = service(Optional.of(EXE));
		s.onReady();
		assertThat(registry).containsEntry("PlacementTracker", COMMAND);
		assertThat(Files.exists(dir.resolve("autostart-initialised"))).isTrue();
		assertThat(s.enabled()).isTrue();
	}

	@Test
	void secondLaunchRespectsUserTurningItOff() throws Exception {
		Files.writeString(dir.resolve("autostart-initialised"), "");
		service(Optional.of(EXE)).onReady();
		assertThat(registry).isEmpty();
	}

	@Test
	void movedExeIsRewritten() throws Exception {
		Files.writeString(dir.resolve("autostart-initialised"), "");
		registry.put("PlacementTracker", "\"C:\\Old\\Placement Tracker.exe\" --autostart");
		service(Optional.of(EXE)).onReady();
		assertThat(registry).containsEntry("PlacementTracker", COMMAND);
	}

	@Test
	void notAvailableNeverWrites() {
		AutostartService s = service(Optional.empty());
		s.onReady();
		assertThat(s.available()).isFalse();
		assertThat(registry).isEmpty();
		assertThat(Files.exists(dir.resolve("autostart-initialised"))).isFalse();
		assertThatThrownBy(() -> s.setEnabled(true)).isInstanceOf(IllegalStateException.class);
		assertThat(registry).isEmpty();
	}

	@Test
	void disableDeletesValue() {
		AutostartService s = service(Optional.of(EXE));
		s.setEnabled(true);
		assertThat(s.enabled()).isTrue();
		s.setEnabled(false);
		assertThat(registry).isEmpty();
		assertThat(s.enabled()).isFalse();
	}

	@Test
	void registryFailureAtStartupDoesNotThrow() {
		RunKey broken = new RunKey() {
			public Optional<String> read(String name) { throw new IllegalStateException("boom"); }
			public void write(String name, String command) { throw new IllegalStateException("boom"); }
			public void delete(String name) { throw new IllegalStateException("boom"); }
		};
		new AutostartService(broken, () -> Optional.of(EXE), dataDir).onReady();
	}
}
