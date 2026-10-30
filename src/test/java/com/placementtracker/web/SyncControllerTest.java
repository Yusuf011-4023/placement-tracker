package com.placementtracker.web;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
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
	void connectWithoutClientSecretIs409() throws Exception {
		mvc.perform(own(post("/api/accounts/GMAIL/connect")))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error", containsString("google-client-secret.json")))
				.andExpect(jsonPath("$.error", containsString("client_secret_")))
				.andExpect(jsonPath("$.error", containsString(tmp.toString())));
	}

	@Test
	void accountsExposeLastConnectError() throws Exception {
		mvc.perform(get("/api/accounts").header("Host", "localhost:8765"))
				.andExpect(jsonPath("$[0].lastConnectError").value(nullValue()));
	}

	@Test
	void connectUnknownAccountIs404() throws Exception {
		mvc.perform(own(post("/api/accounts/NOPE/connect"))).andExpect(status().isNotFound());
		mvc.perform(own(post("/api/accounts/OUTLOOK/connect"))).andExpect(status().isNotFound());
	}
}
