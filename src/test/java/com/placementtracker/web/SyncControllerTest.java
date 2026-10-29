package com.placementtracker.web;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;

class SyncControllerTest extends ApiTestBase {

	@Test
	void syncReturns202() throws Exception {
		mvc.perform(own(post("/api/sync"))).andExpect(status().isAccepted());
		// a second call while the first may still be running is also accepted, never queued or rejected
		mvc.perform(own(post("/api/sync"))).andExpect(status().isAccepted());
	}

	@Test
	void accountsListsSources() throws Exception {
		mvc.perform(get("/api/accounts").header("Host", "localhost:8765"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$", hasSize(1)))
				.andExpect(jsonPath("$[0].account", is("GMAIL")))
				.andExpect(jsonPath("$[0].connected").isBoolean());
	}

	@Test
	void connectUnknownAccountIs404() throws Exception {
		mvc.perform(own(post("/api/accounts/NOPE/connect"))).andExpect(status().isNotFound());
		mvc.perform(own(post("/api/accounts/OUTLOOK/connect"))).andExpect(status().isNotFound());
	}
}
