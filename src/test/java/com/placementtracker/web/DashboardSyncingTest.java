package com.placementtracker.web;

import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.placementtracker.sync.SyncService;

class DashboardSyncingTest extends ApiTestBase {

	@MockitoBean
	SyncService syncService;

	@Test
	void dashboardShowsSyncingDuringScheduledSync() throws Exception {
		when(syncService.isRunning()).thenReturn(true); // no "Check now": the scheduler or first connect started it

		mvc.perform(get("/api/dashboard").header("Host", "localhost:8765")).andExpect(jsonPath("$.syncing", is(true)));

		when(syncService.isRunning()).thenReturn(false);
		mvc.perform(get("/api/dashboard").header("Host", "localhost:8765"))
				.andExpect(jsonPath("$.syncing", is(false)));
	}
}
