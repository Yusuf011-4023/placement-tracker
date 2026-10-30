package com.placementtracker.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;

class LocalOnlyFilterTest extends ApiTestBase {

	@Test
	void mutatingWithoutHeaderIs403() throws Exception {
		mvc.perform(post("/api/sync").header("Host", "localhost:8765")).andExpect(status().isForbidden());
		mvc.perform(patch("/api/applications/1").header("Host", "127.0.0.1:8765").contentType("application/json")
				.content("{}")).andExpect(status().isForbidden());
		mvc.perform(delete("/api/applications/1").header("Host", "localhost:8765")).andExpect(status().isForbidden());
		mvc.perform(post("/api/sync").header("Host", "localhost:8765").header("X-Tracker", "0"))
				.andExpect(status().isForbidden());
	}

	@Test
	void foreignHostHeaderIs403() throws Exception {
		mvc.perform(get("/api/dashboard").header("Host", "evil.example")).andExpect(status().isForbidden());
		mvc.perform(get("/api/dashboard").header("Host", "localhost")).andExpect(status().isForbidden());
		mvc.perform(get("/api/dashboard").header("Host", "localhost:8766")).andExpect(status().isForbidden());
		mvc.perform(get("/api/dashboard").header("Host", "127.0.0.1:8765.evil.example"))
				.andExpect(status().isForbidden());
		mvc.perform(post("/api/sync").header("Host", "evil.example").header("X-Tracker", "1"))
				.andExpect(status().isForbidden());
	}

	@Test
	void staticFilesAreHostChecked() throws Exception {
		mvc.perform(get("/").header("Host", "evil.example")).andExpect(status().isForbidden());
		mvc.perform(get("/app.js").header("Host", "evil.example")).andExpect(status().isForbidden());
		mvc.perform(get("/style.css").header("Host", "evil.example")).andExpect(status().isForbidden());
		mvc.perform(get("/index.html").header("Host", "evil.example")).andExpect(status().isForbidden());
	}

	@Test
	void allowedHostsPassTheFilter() throws Exception {
		mvc.perform(get("/api/dashboard").header("Host", "localhost:8765")).andExpect(status().isOk());
		mvc.perform(get("/api/dashboard").header("Host", "127.0.0.1:8765")).andExpect(status().isOk());
		mvc.perform(get("/api/dashboard")).andExpect(status().isForbidden()); // MockMvc default Host has no port
	}
}
