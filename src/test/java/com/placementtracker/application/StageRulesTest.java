package com.placementtracker.application;

import static com.placementtracker.application.Stage.*;
import static com.placementtracker.application.TransitionDecision.Kind.APPLY;
import static com.placementtracker.application.TransitionDecision.Kind.NO_CHANGE;
import static com.placementtracker.application.TransitionDecision.Kind.REVIEW;
import static org.assertj.core.api.Assertions.assertThat;

import com.placementtracker.application.TransitionDecision.Kind;
import com.placementtracker.review.ReviewReason;
import org.junit.jupiter.api.Test;

class StageRulesTest {

    @Test void sameStageIsNoChange()            { assertKind(INTERVIEW, INTERVIEW, false, NO_CHANGE); }
    @Test void lockedStageChangeGoesToReview()  { assertReview(INTERVIEW, REJECTED, true, ReviewReason.EDITED_FIELD); }
    @Test void notSubmittedCanMoveAnywhere()    { assertKind(NOT_SUBMITTED, APPLIED, false, APPLY); }
    @Test void midProcessMovesFreely()          { assertKind(ASSESSMENT_CENTRE, INTERVIEW, false, APPLY);
                                                  assertKind(INTERVIEW, ONLINE_TEST, false, APPLY); }
    @Test void backToAppliedIsRegression()      { assertReview(ONLINE_TEST, APPLIED, false, ReviewReason.REGRESSION); }
    @Test void backToNotSubmittedIsRegression() { assertReview(APPLIED, NOT_SUBMITTED, false, ReviewReason.REGRESSION); }
    @Test void anyChangeFromClosedIsReviewed()  { assertReview(OFFER, WITHDRAWN, false, ReviewReason.CLOSED_CHANGE);
                                                  assertReview(REJECTED, OFFER, false, ReviewReason.CLOSED_CHANGE);
                                                  assertReview(REJECTED, INTERVIEW, false, ReviewReason.CLOSED_CHANGE); }
    @Test void activeToClosedApplies()          { assertKind(APPLIED, REJECTED, false, APPLY); }

    // precedence edges: same stage beats the lock, the lock beats closed, closed beats regression
    @Test void lockedSameStageIsNoChange()      { assertKind(INTERVIEW, INTERVIEW, true, NO_CHANGE); }
    @Test void lockedClosedIsEditedField()      { assertReview(REJECTED, INTERVIEW, true, ReviewReason.EDITED_FIELD); }
    @Test void notSubmittedToClosedApplies()    { assertKind(NOT_SUBMITTED, REJECTED, false, APPLY);
                                                  assertKind(NOT_SUBMITTED, WITHDRAWN, false, APPLY); }
    @Test void appliedToMidProcessApplies()     { assertKind(APPLIED, ONLINE_TEST, false, APPLY);
                                                  assertKind(APPLIED, ASSESSMENT_CENTRE, false, APPLY); }
    @Test void closedToNotSubmittedIsClosedChange() { assertReview(REJECTED, NOT_SUBMITTED, false, ReviewReason.CLOSED_CHANGE);
                                                  assertReview(OFFER, NOT_SUBMITTED, false, ReviewReason.CLOSED_CHANGE); }

    private static void assertKind(Stage current, Stage proposed, boolean locked, Kind expected) {
        TransitionDecision decision = StageRules.decide(current, proposed, locked);
        assertThat(decision.kind()).as("%s -> %s (locked=%s)", current, proposed, locked).isEqualTo(expected);
    }

    private static void assertReview(Stage current, Stage proposed, boolean locked, ReviewReason expected) {
        TransitionDecision decision = StageRules.decide(current, proposed, locked);
        assertThat(decision.kind()).as("%s -> %s (locked=%s)", current, proposed, locked).isEqualTo(REVIEW);
        assertThat(decision.reason()).as("%s -> %s reason", current, proposed).isEqualTo(expected);
    }
}
