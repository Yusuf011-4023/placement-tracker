package com.placementtracker.dev;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.placementtracker.application.ActionRepository;
import com.placementtracker.application.ApplicationEditService;
import com.placementtracker.application.ApplicationRepository;
import com.placementtracker.application.Stage;
import com.placementtracker.mail.EmailRepository;
import com.placementtracker.mail.MailAccount;
import com.placementtracker.mail.MailMessage;
import com.placementtracker.review.ReviewReason;
import com.placementtracker.review.ReviewRepository;

/**
 * Fills an empty database with the made-up applications from the approved mockup, so the web UI can be looked at
 * without a Gmail connection. Only ever called from {@link DevCommands} behind {@code tracker.dev.seed=true}.
 */
@Component
public class DevSeeder {

	private static final Logger log = LoggerFactory.getLogger(DevSeeder.class);

	private final ApplicationRepository apps;
	private final ApplicationEditService edits;
	private final ActionRepository actions;
	private final EmailRepository emails;
	private final ReviewRepository reviews;
	private final Clock clock;

	public DevSeeder(ApplicationRepository apps, ApplicationEditService edits, ActionRepository actions,
			EmailRepository emails, ReviewRepository reviews, Clock clock) {
		this.apps = apps;
		this.edits = edits;
		this.actions = actions;
		this.emails = emails;
		this.reviews = reviews;
		this.clock = clock;
	}

	/** Inserts the sample data and returns true, or does nothing and returns false when applications already exist. */
	public boolean seedIfEmpty() {
		if (!apps.findAll().isEmpty()) {
			log.info("dev seed skipped: the database already has applications");
			return false;
		}
		long northwind = app("Northwind Bank", "Technology Placement", Stage.ONLINE_TEST);
		todo(northwind, "Complete online test", Duration.ofHours(20), Stage.ONLINE_TEST);

		long brightline = app("Brightline Energy", "Data Analyst Placement", Stage.INTERVIEW);
		todo(brightline, "Book video interview", Duration.ofDays(3).minusHours(4), Stage.INTERVIEW);

		long halcyon = app("Halcyon Consulting", "Business Analyst Placement", Stage.ASSESSMENT_CENTRE);
		todo(halcyon, "Attend assessment centre", Duration.ofDays(9), Stage.ASSESSMENT_CENTRE);

		app("Quarry Labs", "SWE Intern", Stage.APPLIED);
		long pinecrest = app("Pinecrest Media", "Digital Placement", Stage.APPLIED);
		todo(pinecrest, "Upload your transcript", Duration.ofDays(-1), Stage.APPLIED);
		app("Atlas Logistics", "IT Placement", Stage.APPLIED);
		app("Keystone Pharma", "Data Placement", Stage.ONLINE_TEST);
		app("Fenwick Retail", "Finance Placement", Stage.OFFER);
		app("Orion Insurance", "Actuarial Placement", Stage.REJECTED);
		app("Cobalt Systems", "Cloud Placement", Stage.NOT_SUBMITTED);

		openPleaseCheck();
		log.info("dev seed: inserted sample applications");
		return true;
	}

	private long app(String company, String role, Stage stage) {
		return edits.create(company, role, stage, null, null);
	}

	private void todo(long appId, String description, Duration fromNow, Stage stage) {
		OffsetDateTime due = clock.instant().plus(fromNow).atOffset(ZoneOffset.UTC);
		actions.insert(appId, description, due, stage, null);
	}

	/** One unsure email, as the classifier would have left it. */
	private void openPleaseCheck() {
		Instant received = clock.instant().minus(Duration.ofHours(5));
		MailMessage m = new MailMessage(MailAccount.GMAIL, "seed-1", "seed-thread-1", "<seed-1@example.test>",
				"Talent Team <talent@polaris-digital.example>", "Your application: next steps", received,
				"Thanks for applying. Please complete the attached assessment.", null, java.util.Set.of());
		long emailId = emails.insertIfNew(m, "seed-dedupe-1").orElseThrow().id();
		String suggestion = """
				{"classification":{"relevance":"UNSURE","company":"Polaris Digital","role":"Product Placement",\
				"stage":"ONLINE_TEST","actions":[{"description":"Complete the assessment","dueAt":null}],\
				"confidence":0.55,"reason":"Could be a newsletter or a real invitation."}}""";
		reviews.open(emailId, null, ReviewReason.LOW_CONFIDENCE, suggestion);
	}
}
