package com.placementtracker.web;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

/** The web UI files are served from the classpath, behind the same Host guard as the API. */
class StaticPagesTest extends ApiTestBase {

	private ResultActions fetch(String path) throws Exception {
		return mvc.perform(get(path).header("Host", "localhost:8765"));
	}

	@Test
	void rootForwardsToTheDashboardPage() throws Exception {
		// MockMvc reports a forward without following it
		fetch("/").andExpect(status().isOk()).andExpect(forwardedUrl("index.html"));
	}

	@Test
	void dashboardPageIsServed() throws Exception {
		fetch("/index.html").andExpect(status().isOk()).andExpect(content().string(containsString("Placement Tracker")));
	}

	@Test
	void setupPageAndAssetsAreServed() throws Exception {
		for (String path : new String[] { "/setup.html", "/app.js", "/setup.js", "/common.js", "/styles.css" }) {
			fetch(path).andExpect(status().isOk());
		}
	}
}
