package com.placementtracker.web;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.placementtracker.application.Stage;
import com.placementtracker.classify.AiHealth;

class DashboardControllerTest extends ApiTestBase {

	@Test
	void dashboardGroupsBoardAndCountsChart() throws Exception {
		app("Acme", Stage.APPLIED);
		app("Beta", Stage.APPLIED);
		long i = app("Gamma", Stage.INTERVIEW);
		app("Delta", Stage.OFFER);
		app("Eps", Stage.REJECTED);
		action(i, "Prepare", 5, Stage.INTERVIEW);

		mvc.perform(get("/api/dashboard").header("Host", "localhost:8765"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.board.APPLIED", hasSize(2)))
				.andExpect(jsonPath("$.board.INTERVIEW", hasSize(1)))
				.andExpect(jsonPath("$.board.INTERVIEW[0].company", is("Gamma")))
				.andExpect(jsonPath("$.board.INTERVIEW[0].hasOpenAction", is(true)))
				.andExpect(jsonPath("$.board.INTERVIEW[0].urgency", is("URGENT")))
				.andExpect(jsonPath("$.board.APPLIED[0].hasOpenAction", is(false)))
				.andExpect(jsonPath("$.board.OUTCOME", hasSize(2)))
				.andExpect(jsonPath("$.board.ONLINE_TEST", hasSize(0)))
				.andExpect(jsonPath("$.chart[0].stage", is("APPLIED")))
				.andExpect(jsonPath("$.chart[0].count", is(2)))
				.andExpect(jsonPath("$.chart[2].stage", is("INTERVIEW")))
				.andExpect(jsonPath("$.chart[2].count", is(1)));
	}

	@Test
	void chartIncludesZeroCountsAndClosedBucket() throws Exception {
		app("Eps", Stage.REJECTED);
		app("Zed", Stage.WITHDRAWN);
		app("Draft", Stage.NOT_SUBMITTED);

		mvc.perform(get("/api/dashboard").header("Host", "localhost:8765"))
				.andExpect(jsonPath("$.chart", hasSize(6)))
				.andExpect(jsonPath("$.chart[0].stage", is("APPLIED")))
				.andExpect(jsonPath("$.chart[0].count", is(0)))
				.andExpect(jsonPath("$.chart[1].stage", is("ONLINE_TEST")))
				.andExpect(jsonPath("$.chart[1].count", is(0)))
				.andExpect(jsonPath("$.chart[2].stage", is("INTERVIEW")))
				.andExpect(jsonPath("$.chart[3].stage", is("ASSESSMENT_CENTRE")))
				.andExpect(jsonPath("$.chart[4].stage", is("OFFER")))
				.andExpect(jsonPath("$.chart[4].count", is(0)))
				.andExpect(jsonPath("$.chart[5].stage", is("CLOSED")))
				.andExpect(jsonPath("$.chart[5].count", is(2)));
	}

	@Test
	void needsActionSortedByDueWithUrgency() throws Exception {
		long a = app("Acme", Stage.ONLINE_TEST);
		action(a, "later", 200, Stage.ONLINE_TEST);
		action(a, "soon", 60, Stage.ONLINE_TEST);
		action(a, "overdue", -2, Stage.ONLINE_TEST);
		action(a, "urgent", 20, Stage.ONLINE_TEST);
		actions.insert(a, "undated", null, Stage.ONLINE_TEST, null);

		mvc.perform(get("/api/dashboard").header("Host", "localhost:8765"))
				.andExpect(jsonPath("$.needsAction", hasSize(5)))
				.andExpect(jsonPath("$.needsAction[0].description", is("overdue")))
				.andExpect(jsonPath("$.needsAction[0].urgency", is("OVERDUE")))
				.andExpect(jsonPath("$.needsAction[1].description", is("urgent")))
				.andExpect(jsonPath("$.needsAction[1].urgency", is("URGENT")))
				.andExpect(jsonPath("$.needsAction[2].description", is("soon")))
				.andExpect(jsonPath("$.needsAction[2].urgency", is("SOON")))
				.andExpect(jsonPath("$.needsAction[3].description", is("later")))
				.andExpect(jsonPath("$.needsAction[3].urgency", is("LATER")))
				.andExpect(jsonPath("$.needsAction[4].description", is("undated")))
				.andExpect(jsonPath("$.needsAction[4].urgency", is("LATER")))
				.andExpect(jsonPath("$.needsAction[0].company", is("Acme")))
				.andExpect(jsonPath("$.needsAction[0].role", is("Engineer")));
	}

	@Test
	void notSubmittedOnlyInNeedsAction() throws Exception {
		long d = app("Draft", Stage.NOT_SUBMITTED);
		action(d, "Finish your Draft application", 30, Stage.NOT_SUBMITTED);

		mvc.perform(get("/api/dashboard").header("Host", "localhost:8765"))
				.andExpect(jsonPath("$.needsAction", hasSize(1)))
				.andExpect(jsonPath("$.needsAction[0].applicationId", is((int) d)))
				.andExpect(jsonPath("$.board.APPLIED", hasSize(0)))
				.andExpect(jsonPath("$.board.OUTCOME", hasSize(0)))
				.andExpect(jsonPath("$.chart[0].count", is(0)));
	}

	@Test
	void markDoneRemovesFromNeedsAction() throws Exception {
		long a = app("Acme", Stage.APPLIED);
		long act = action(a, "Do it", 10, Stage.APPLIED);

		mvc.perform(own(post("/api/actions/" + act + "/done"))).andExpect(status().isOk());

		mvc.perform(get("/api/dashboard").header("Host", "localhost:8765"))
				.andExpect(jsonPath("$.needsAction", hasSize(0)))
				.andExpect(jsonPath("$.board.APPLIED[0].hasOpenAction", is(false)));
	}

	@Test
	void markDoneOnMissingActionIs404() throws Exception {
		mvc.perform(own(post("/api/actions/999/done"))).andExpect(status().isNotFound());
	}

	@Test
	void dashboardReportsFlagsAndAccounts() throws Exception {
		mvc.perform(get("/api/dashboard").header("Host", "localhost:8765"))
				.andExpect(jsonPath("$.aiConfigured").isBoolean())
				.andExpect(jsonPath("$.syncing").isBoolean())
				.andExpect(jsonPath("$.accounts[0].account", is("GMAIL")))
				.andExpect(jsonPath("$.accounts[0].connected").isBoolean())
				.andExpect(jsonPath("$.accounts[0].status", is("NOT_CONNECTED")))
				.andExpect(jsonPath("$.pleaseCheck", hasSize(0)));
	}

	@Autowired
	AiHealth aiHealth;

	@Test
	void dashboardShowsAiErrorAndWaitingCount() throws Exception {
		for (String st : new String[] { "PENDING", "PENDING", "FAILED", "PROCESSED", "REVIEW" }) {
			jdbc.update("INSERT INTO emails (account, message_id, status, attempts) VALUES ('GMAIL', ?, ?, 0)",
					"m-" + java.util.UUID.randomUUID(), st);
		}
		aiHealth.recordFailure("Anthropic API error 402: credit balance too low");

		mvc.perform(get("/api/dashboard").header("Host", "localhost:8765"))
				.andExpect(jsonPath("$.ai.configured").isBoolean())
				.andExpect(jsonPath("$.ai.lastError", is("Anthropic API error 402: credit balance too low")))
				.andExpect(jsonPath("$.ai.lastErrorAt").isNotEmpty())
				.andExpect(jsonPath("$.ai.waiting", is(3)))
				.andExpect(jsonPath("$.aiConfigured").isBoolean());

		aiHealth.recordSuccess();
		mvc.perform(get("/api/dashboard").header("Host", "localhost:8765"))
				.andExpect(jsonPath("$.ai.lastSuccessAt").isNotEmpty());
	}
}
