package com.placementtracker.matching;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.placementtracker.application.Application;
import com.placementtracker.application.ApplicationRepository;
import com.placementtracker.classify.Classification;
import com.placementtracker.mail.MailMessage;
import com.placementtracker.matching.MatchResult.Ambiguous;
import com.placementtracker.matching.MatchResult.Existing;
import com.placementtracker.matching.MatchResult.New;

/** Decides whether a classified email belongs to an existing application, a new one, or needs review. */
@Component
public class ApplicationMatcher {

	private static final Set<String> ROLE_STOPWORDS = Set.of("placement", "internship", "intern", "year",
			"industrial", "the", "and", "2026", "2027");

	private final ApplicationRepository apps;

	public ApplicationMatcher(ApplicationRepository apps) {
		this.apps = apps;
	}

	public MatchResult match(Classification c, MailMessage m) {
		var linked = apps.findByThreadId(m.account().name(), m.threadId());
		if (linked.isPresent()) {
			// A reply thread usually stays with one company, but forwarded or recruiter-agency threads can cross
			// over: when the email clearly names a different company, let the owner decide.
			String classified = CompanyNames.key(c.company());
			String threadCompany = apps.find(linked.get()).map(Application::companyKey).orElse("");
			if (!classified.isEmpty() && !threadCompany.isEmpty() && !classified.equals(threadCompany)) {
				return new Ambiguous(List.of(linked.get()));
			}
			return new Existing(linked.get());
		}
		if (c.company() == null || c.company().isBlank()) {
			return new Ambiguous(List.of());
		}
		String key = CompanyNames.key(c.company());
		if (key.isEmpty()) {
			return new Ambiguous(List.of());
		}
		List<Application> candidates = apps.findByCompanyKey(key);
		if (candidates.isEmpty()) {
			return new New();
		}
		List<Application> open = candidates.stream().filter(a -> !a.stage().isClosed()).toList();
		if (open.isEmpty()) {
			return new Ambiguous(ids(candidates));
		}
		if (open.size() == 1) {
			Application only = open.get(0);
			return similarRole(only.role(), c.role()) ? new Existing(only.id()) : new Ambiguous(ids(open));
		}
		List<Application> similar = open.stream().filter(a -> similarRole(a.role(), c.role())).toList();
		if (similar.size() == 1) {
			return new Existing(similar.get(0).id());
		}
		return new Ambiguous(ids(open));
	}

	private static List<Long> ids(List<Application> apps) {
		return apps.stream().map(Application::id).toList();
	}

	/**
	 * Blank on either side counts as similar. Otherwise token Jaccard of the role words (generic words removed)
	 * must be at least 0.5. A role made only of generic words (e.g. \"Placement\") counts as blank.
	 */
	static boolean similarRole(String a, String b) {
		if (isBlank(a) || isBlank(b)) {
			return true;
		}
		Set<String> ta = roleTokens(a);
		Set<String> tb = roleTokens(b);
		if (ta.isEmpty() || tb.isEmpty()) {
			return true;
		}
		Set<String> union = new HashSet<>(ta);
		union.addAll(tb);
		Set<String> inter = new HashSet<>(ta);
		inter.retainAll(tb);
		return !union.isEmpty() && (double) inter.size() / union.size() >= 0.5;
	}

	private static Set<String> roleTokens(String role) {
		Set<String> out = new HashSet<>();
		for (String t : normalise(role).split(" ")) {
			if (!t.isEmpty() && !ROLE_STOPWORDS.contains(t)) {
				out.add(t);
			}
		}
		return out;
	}

	private static String normalise(String role) {
		return role.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
	}

	private static boolean isBlank(String s) {
		return s == null || s.isBlank();
	}
}
