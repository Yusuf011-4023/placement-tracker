package com.placementtracker.application;

import com.placementtracker.review.ReviewReason;

public record TransitionDecision(Kind kind, ReviewReason reason) {

    public enum Kind { APPLY, NO_CHANGE, REVIEW }
}
