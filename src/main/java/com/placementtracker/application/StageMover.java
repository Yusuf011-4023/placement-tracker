package com.placementtracker.application;

import org.springframework.stereotype.Component;

/** The one place that moves an application to a different stage (used by email processing and by the owner). */
@Component
public class StageMover {

	private final ApplicationRepository apps;
	private final ActionRepository actions;
	private final HistoryRepository history;

	public StageMover(ApplicationRepository apps, ActionRepository actions, HistoryRepository history) {
		this.apps = apps;
		this.actions = actions;
		this.history = history;
	}

	/**
	 * Closes the old stage's open actions, sets the stage, records history, counts the interview round and, for a
	 * closed stage, closes every open action. Does nothing when the stage is unchanged.
	 *
	 * @param roundOnlyIfFirst true: entering INTERVIEW sets rounds to 1 only when it was 0 (owner edit);
	 *                         false: entering INTERVIEW always adds a round (email evidence)
	 * @return true when the stage changed
	 */
	public boolean move(Application app, Stage to, String source, Long emailId, boolean roundOnlyIfFirst) {
		if (to == app.stage()) {
			return false;
		}
		long id = app.id();
		actions.closeOpenForStage(id, app.stage());
		apps.updateStage(id, to);
		history.record(id, app.stage(), to, source, emailId);
		if (to == Stage.INTERVIEW && (!roundOnlyIfFirst || app.interviewRounds() == 0)) {
			apps.incrementInterviewRounds(id);
		}
		if (to.isClosed()) {
			actions.closeAllOpen(id);
		}
		return true;
	}
}
