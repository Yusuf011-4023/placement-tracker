package com.placementtracker.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.placementtracker.classify.Classification;
import com.placementtracker.classify.ClassificationException;
import com.placementtracker.classify.ClaudeEmailClassifier;
import com.placementtracker.classify.Relevance;
import com.placementtracker.matching.CompanyNames;

/**
 * Runs the real classifier (a paid API call per case) over the evaluation set. Excluded from the default build;
 * run with {@code ./mvnw -q test -Peval}. Skipped when no Anthropic API key is configured.
 */
@Tag("eval")
@SpringBootTest
class ClassificationEvalTest {

	static final double MIN_YES_RECALL = 0.95;
	static final double MIN_STAGE_ACCURACY = 0.85;

	@Autowired
	private ClaudeEmailClassifier classifier;

	@Test
	void classifierMeetsRecallAndStageThresholds() throws Exception {
		assumeTrue(classifier.isConfigured(),
				"No Anthropic API key configured (set ANTHROPIC_API_KEY or anthropic.api-key in config.properties); "
						+ "skipping the classification eval");

		List<EvalCase> cases = EvalCase.loadAll();
		int yes = 0;
		int yesRecalled = 0;
		int stageCorrect = 0;
		int companyCorrect = 0;
		int relevanceCorrect = 0;
		int noCases = 0;
		int noCorrect = 0;

		System.out.printf("%n%-40s | %-16s | %-26s | %-18s | %s%n", "id", "relevance (e/a)", "company (e/a)",
				"stage (e/a)", "ok");
		System.out.println("-".repeat(130));
		for (EvalCase c : cases) {
			Classification r = classify(c);
			boolean relOk = r.relevance() == c.expectedRelevance();
			boolean recalled = c.expectedRelevance() == Relevance.YES && r.relevance() != Relevance.NO;
			boolean companyOk = true;
			boolean stageOk = true;
			if (c.expectedRelevance() == Relevance.YES) {
				yes++;
				if (recalled) {
					yesRecalled++;
				}
				companyOk = r.company() != null
						&& CompanyNames.key(r.company()).equals(CompanyNames.key(c.expectedCompany()));
				stageOk = r.relevance() == Relevance.YES && r.stage() == c.expectedStage();
				if (companyOk) {
					companyCorrect++;
				}
				if (stageOk) {
					stageCorrect++;
				}
			}
			if (c.expectedRelevance() == Relevance.NO) {
				noCases++;
				if (relOk) {
					noCorrect++;
				}
			}
			if (relOk) {
				relevanceCorrect++;
			}
			boolean ok = relOk && companyOk && stageOk;
			System.out.printf("%-40s | %-7s/%-7s  | %-26s | %-18s | %s%n", c.id(),
					c.expectedRelevance(), r.relevance(),
					abbreviate(c.expectedCompany()) + " / " + abbreviate(r.company()),
					c.expectedStage() + " / " + r.stage(), ok ? "✓" : "✗");
		}

		double recall = yes == 0 ? 1.0 : (double) yesRecalled / yes;
		double stageAccuracy = yes == 0 ? 1.0 : (double) stageCorrect / yes;
		System.out.println("-".repeat(130));
		System.out.printf("Cases: %d (YES %d, NO %d)%n", cases.size(), yes, noCases);
		System.out.printf("Relevance accuracy (exact): %d/%d = %.3f%n", relevanceCorrect, cases.size(),
				(double) relevanceCorrect / cases.size());
		System.out.printf("YES-recall (UNSURE counts as recalled): %d/%d = %.3f (min %.2f)%n", yesRecalled, yes, recall,
				MIN_YES_RECALL);
		System.out.printf("NO accuracy: %d/%d%n", noCorrect, noCases);
		System.out.printf("Company accuracy (YES cases): %d/%d = %.3f%n", companyCorrect, yes,
				yes == 0 ? 1.0 : (double) companyCorrect / yes);
		System.out.printf("Stage accuracy (YES cases): %d/%d = %.3f (min %.2f)%n", stageCorrect, yes, stageAccuracy,
				MIN_STAGE_ACCURACY);

		assertThat(recall).as("YES-recall").isGreaterThanOrEqualTo(MIN_YES_RECALL);
		assertThat(stageAccuracy).as("stage accuracy on YES cases").isGreaterThanOrEqualTo(MIN_STAGE_ACCURACY);
	}

	private Classification classify(EvalCase c) throws ClassificationException {
		try {
			return classifier.classify(c.email());
		}
		catch (ClassificationException e) {
			if (e.isRetryable()) {
				// One retry for a transient API error, so a blip does not fail the whole run.
				return classifier.classify(c.email());
			}
			throw e;
		}
	}

	private static String abbreviate(String s) {
		if (s == null) {
			return "-";
		}
		return s.length() <= 11 ? s : s.substring(0, 10) + "…";
	}
}
