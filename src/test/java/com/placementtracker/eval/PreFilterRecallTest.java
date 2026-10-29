package com.placementtracker.eval;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.placementtracker.classify.Relevance;
import com.placementtracker.mail.PreFilter;

/**
 * Every email the owner would want tracked must get past the pre-filter. Uses the real keyword and domain lists
 * from application.yml, so a gap in the configuration shows up here.
 */
@SpringBootTest
class PreFilterRecallTest {

	@Autowired
	private PreFilter preFilter;

	@Test
	void evalSetHasEnoughCases() {
		List<EvalCase> cases = EvalCase.loadAll();
		assertThat(cases).hasSizeGreaterThanOrEqualTo(40);
		assertThat(cases.stream().filter(c -> c.expectedRelevance() == Relevance.NO).count())
			.isGreaterThanOrEqualTo(12);
	}

	@Test
	void everyYesCasePassesThePreFilter() {
		List<String> dropped = EvalCase.loadAll()
			.stream()
			.filter(c -> c.expectedRelevance() == Relevance.YES)
			.filter(c -> !preFilter.shouldClassify(c.email()))
			.map(EvalCase::id)
			.toList();

		assertThat(dropped).as("YES cases the pre-filter would wrongly discard").isEmpty();
	}
}
