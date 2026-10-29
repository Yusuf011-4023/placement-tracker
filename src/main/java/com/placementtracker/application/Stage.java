package com.placementtracker.application;

public enum Stage {
    NOT_SUBMITTED,
    APPLIED,
    ONLINE_TEST,
    INTERVIEW,
    ASSESSMENT_CENTRE,
    OFFER,
    REJECTED,
    WITHDRAWN;

    public boolean isClosed() {
        return this == OFFER || this == REJECTED || this == WITHDRAWN;
    }

    public boolean isMidProcess() {
        return this == ONLINE_TEST || this == INTERVIEW || this == ASSESSMENT_CENTRE;
    }

    public boolean onBoard() {
        return this != NOT_SUBMITTED;
    }
}
