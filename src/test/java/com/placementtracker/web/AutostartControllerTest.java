package com.placementtracker.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;

import com.placementtracker.config.DataDir;
import com.placementtracker.launch.AutostartService;
import com.placementtracker.launch.RunKey;

/** Uses a fake RunKey: the real registry is never touched. */
@Import(AutostartControllerTest.FakeAutostart.class)
class AutostartControllerTest extends ApiTestBase {

	static final Map<String, String> REGISTRY = new ConcurrentHashMap<>();
	static final AtomicReference<Optional<String>> EXE = new AtomicReference<>(Optional.empty());

	@TestConfiguration
	static class FakeAutostart {
		@Bean
		@Primary
		AutostartService fakeAutostart(DataDir dataDir) {
			RunKey fake = new RunKey() {
				public Optional<String> read(String name) { return Optional.ofNullable(REGISTRY.get(name)); }
				public void write(String name, String command) { REGISTRY.put(name, command); }
				public void delete(String name) { REGISTRY.remove(name); }
			};
			return new AutostartService(fake, EXE::get, dataDir);
		}
	}

	@Autowired AutostartService service;

	@BeforeEach
	void reset() {
		REGISTRY.clear();
		EXE.set(Optional.of("C:\\Apps\\Placement Tracker\\Placement Tracker.exe"));
	}

	@Test
	void getReportsShape() throws Exception {
		mvc.perform(get("/api/autostart").header("Host", "localhost:8765"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.available").value(true))
				.andExpect(jsonPath("$.enabled").value(false));
	}

	@Test
	void putToggles() throws Exception {
		mvc.perform(own(put("/api/autostart")).content("{\"enabled\":true}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.enabled").value(true));
		org.junit.jupiter.api.Assertions.assertTrue(REGISTRY.containsKey("PlacementTracker"));
		mvc.perform(own(put("/api/autostart")).content("{\"enabled\":false}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.enabled").value(false));
		org.junit.jupiter.api.Assertions.assertTrue(REGISTRY.isEmpty());
	}

	@Test
	void putWithoutTrackerHeaderIsRejected() throws Exception {
		mvc.perform(put("/api/autostart").header("Host", "localhost:8765").contentType(MediaType.APPLICATION_JSON)
				.content("{\"enabled\":true}")).andExpect(status().isForbidden());
		org.junit.jupiter.api.Assertions.assertTrue(REGISTRY.isEmpty());
	}

	@Test
	void putWhenUnavailableIs409() throws Exception {
		EXE.set(Optional.empty());
		mvc.perform(own(put("/api/autostart")).content("{\"enabled\":true}"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error").value("Auto-start is only available in the installed app"));
		mvc.perform(get("/api/autostart").header("Host", "localhost:8765"))
				.andExpect(jsonPath("$.available").value(false));
	}
}
