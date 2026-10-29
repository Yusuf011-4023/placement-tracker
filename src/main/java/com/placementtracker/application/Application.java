package com.placementtracker.application;

import java.time.LocalDate;
import java.util.Set;

public record Application(long id, String company, String companyKey, String role, Stage stage,
		int interviewRounds, LocalDate appliedOn, String notes, Set<String> lockedFields) {
}
