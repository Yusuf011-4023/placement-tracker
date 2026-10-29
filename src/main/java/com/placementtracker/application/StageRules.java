package com.placementtracker.application;

import com.placementtracker.review.ReviewReason;

public final class StageRules {

    private StageRules() {
    }

    public static TransitionDecision decide(Stage current, Stage proposed, boolean stageLocked) {
        if (current == proposed) {
            return new TransitionDecision(TransitionDecision.Kind.NO_CHANGE, null);
        }
        if (stageLocked) {
            return review(ReviewReason.EDITED_FIELD);
        }
        if (current.isClosed()) {
            return review(ReviewReason.CLOSED_CHANGE);
        }
        if (proposed == Stage.NOT_SUBMITTED || (proposed == Stage.APPLIED && current.isMidProcess())) {
            return review(ReviewReason.REGRESSION);
        }
        return new TransitionDecision(TransitionDecision.Kind.APPLY, null);
    }

    private static TransitionDecision review(ReviewReason reason) {
        return new TransitionDecision(TransitionDecision.Kind.REVIEW, reason);
    }
}
