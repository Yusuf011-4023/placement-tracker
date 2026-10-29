package com.placementtracker.sync;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.placementtracker.classify.Classification;
import com.placementtracker.classify.ClassificationException;
import com.placementtracker.classify.EmailClassifier;
import com.placementtracker.config.TrackerProperties;
import com.placementtracker.mail.DedupeKeys;
import com.placementtracker.mail.EmailRecord;
import com.placementtracker.mail.EmailRepository;
import com.placementtracker.mail.EmailStatus;
import com.placementtracker.mail.MailAccount;
import com.placementtracker.mail.MailAuthException;
import com.placementtracker.mail.MailMessage;
import com.placementtracker.mail.MailSource;
import com.placementtracker.mail.MailSourceException;
import com.placementtracker.mail.PreFilter;
import com.placementtracker.processing.EmailProcessor;
import com.placementtracker.processing.ProcessOutcome;

/**
 * Pulls mail from every connected source, de-duplicates and pre-filters it, classifies what is left and applies the
 * result in received-date order. Nothing is skipped permanently: the high-water mark moves only after a full listing succeeds, and emails
 * that could not be classified stay in the database (FAILED or PENDING) and are retried on every pass.
 */
@Service
public class SyncService {

	/** Consecutive classification failures after which a run stops calling the classifier. */
	static final int BREAKER_THRESHOLD = 3;

	private static final Logger log = LoggerFactory.getLogger(SyncService.class);

	private final List<MailSource> sources;
	private final PreFilter preFilter;
	private final EmailClassifier classifier;
	private final EmailProcessor processor;
	private final EmailRepository emails;
	private final SyncStateRepository syncState;
	private final FailureRecorder failures;
	private final TrackerProperties props;

	/** Spring constructor: tolerates there being no MailSource beans yet. */
	@Autowired
	public SyncService(ObjectProvider<MailSource> sources, PreFilter preFilter, EmailClassifier classifier,
			EmailProcessor processor, EmailRepository emails, SyncStateRepository syncState, FailureRecorder failures,
			TrackerProperties props) {
		this(sources.orderedStream().toList(), preFilter, classifier, processor, emails, syncState, failures, props);
	}

	public SyncService(List<MailSource> sources, PreFilter preFilter, EmailClassifier classifier,
			EmailProcessor processor, EmailRepository emails, SyncStateRepository syncState, FailureRecorder failures,
			TrackerProperties props) {
		this.sources = List.copyOf(sources);
		this.preFilter = preFilter;
		this.classifier = classifier;
		this.processor = processor;
		this.emails = emails;
		this.syncState = syncState;
		this.failures = failures;
		this.props = props;
	}

	/**
	 * Runs one pass in two steps. First every connected source is listed and each new email is stored, then
	 * de-duplicated or pre-filtered. Then everything still waiting (PENDING, or FAILED under the attempt cap) is
	 * classified and applied in ONE sequence ordered by received date, so an older email is never applied after a
	 * newer one just because it sat in the backlog. A concurrent caller waits for the running pass, then runs its own
	 * (everything is idempotent).
	 */
	public synchronized SyncReport syncAll() {
		Run run = new Run(classifier.isConfigured());
		for (MailSource source : sources) {
			try {
				fetchAndStore(source, run);
			}
			catch (RuntimeException e) {
				log.error("Sync of {} failed unexpectedly", source.account(), e);
				syncState.markError(source.account(), SyncStatus.ERROR, String.valueOf(e.getMessage()));
				run.statuses.put(source.account(), SyncStatus.ERROR);
			}
		}
		try {
			processWaiting(run);
		}
		catch (RuntimeException e) {
			log.error("Processing pass failed", e);
		}
		return run.report(processor.newActionIds());
	}

	/** Step 1: list the source, store what is new, mark duplicates and filtered mail. Classifies nothing. */
	private void fetchAndStore(MailSource source, Run run) {
		MailAccount account = source.account();
		if (!source.isConnected()) {
			run.statuses.put(account, SyncStatus.NOT_CONNECTED);
			return;
		}
		Instant previous = syncState.get(account).highWater();
		Instant since = previous == null ? props.sync().backfillFrom() : previous.minus(1, ChronoUnit.DAYS);
		Instant fetchStarted = Instant.now();
		List<MailMessage> messages;
		try {
			messages = source.fetchSince(since);
		}
		catch (MailAuthException e) {
			log.warn("{} needs reconnecting: {}", account, e.getMessage());
			syncState.markError(account, SyncStatus.NEEDS_RECONNECT, e.getMessage());
			run.statuses.put(account, SyncStatus.NEEDS_RECONNECT);
			return;
		}
		catch (MailSourceException e) {
			log.warn("{} fetch failed: {}", account, e.getMessage());
			syncState.markError(account, SyncStatus.ERROR, e.getMessage());
			run.statuses.put(account, SyncStatus.ERROR);
			return;
		}
		run.fetched += messages.size();
		Instant newest = previous;
		for (MailMessage m : messages) {
			// Capped at fetch start so one future-dated message cannot push the window past real mail.
			Instant seenAt = m.receivedAt() == null ? null
					: (m.receivedAt().isAfter(fetchStarted) ? fetchStarted : m.receivedAt());
			if (seenAt != null && (newest == null || seenAt.isAfter(newest))) {
				newest = seenAt;
			}
			Optional<EmailRecord> rec = emails.insertIfNew(m, DedupeKeys.of(m));
			// Already-stored messages are remembered too: one still waiting then needs no re-fetch.
			if (rec.isEmpty() || screen(rec.get(), m, run)) {
				run.fetchedMessages.put(key(account, m.messageId()), m);
			}
		}
		// Safe before classification: everything new is stored, and step 2 picks it up now or on a later run.
		syncState.markSuccess(account, newest == null ? props.sync().backfillFrom() : newest, Instant.now());
		run.statuses.put(account, SyncStatus.OK);
	}

	/** Marks the email DUPLICATE or FILTERED when it is one; returns true when it still needs classifying. */
	private boolean screen(EmailRecord rec, MailMessage m, Run run) {
		try {
			if (emails.existsDedupeKeyBefore(rec.dedupeKey(), rec.id())) {
				emails.setStatus(rec.id(), EmailStatus.DUPLICATE);
				return false;
			}
			if (!preFilter.shouldClassify(m)) {
				emails.setStatus(rec.id(), EmailStatus.FILTERED);
				return false;
			}
			return true;
		}
		catch (RuntimeException e) {
			recordFailure(rec, e, run);
			return false;
		}
	}

	/**
	 * Step 2: escalate FAILED emails at the attempt cap, then classify and apply every PENDING email and every FAILED
	 * one under the cap, oldest first. A message not listed in this run is re-fetched by id.
	 */
	private void processWaiting(Run run) {
		for (EmailRecord rec : emails.findFailedAtCap(props.ai().maxAttempts())) {
			try {
				if (failures.escalate(rec)) {
					run.newReviewItems++;
				}
			}
			catch (RuntimeException e) {
				log.error("Could not escalate email {}", rec.id(), e);
			}
		}
		if (!run.aiConfigured) {
			return; // everything stays PENDING, no attempt burned; picked up once a key exists
		}
		List<EmailRecord> todo = new ArrayList<>(emails.findRetryable(props.ai().maxAttempts()));
		todo.addAll(emails.findPending());
		todo.sort(OLDEST_FIRST);
		Set<Long> seen = new HashSet<>();
		for (int i = 0; i < todo.size(); i++) {
			EmailRecord rec = todo.get(i);
			if (run.breakerOpen()) {
				break; // AI looks down: leave the rest as they are, no attempt counted
			}
			if (!seen.add(rec.id())) {
				continue;
			}
			MailMessage m = run.fetchedMessages.get(key(rec.account(), rec.messageId()));
			if (m == null) {
				MailSource source = sources.stream().filter(s -> s.account() == rec.account()).findFirst()
						.orElse(null);
				if (source == null || !source.isConnected()) {
					continue;
				}
				Optional<MailMessage> fetched;
				try {
					fetched = source.fetchOne(rec.messageId());
				}
				catch (MailAuthException | MailSourceException | RuntimeException e) {
					log.warn("Could not re-fetch {} for retry: {}", rec.messageId(), e.getMessage());
					continue;
				}
				if (fetched.isEmpty()) {
					log.warn("Message {} ({}) no longer exists; marking FILTERED", rec.messageId(), rec.account());
					emails.setStatus(rec.id(), EmailStatus.FILTERED);
					// a DUPLICATE sibling must not stay stranded behind a copy that no longer exists
					int next = i + 1;
					emails.reviveDuplicate(rec.dedupeKey()).ifPresent(r -> todo.add(next, r));
					continue;
				}
				m = fetched.get();
			}
			handle(rec, m, run);
		}
	}

	/** Dedupe, pre-filter, classify, apply. Never throws for a per-email problem. */
	private void handle(EmailRecord rec, MailMessage m, Run run) {
		try {
			if (emails.existsDedupeKeyBefore(rec.dedupeKey(), rec.id())) {
				emails.setStatus(rec.id(), EmailStatus.DUPLICATE);
				return;
			}
			if (!preFilter.shouldClassify(m)) {
				emails.setStatus(rec.id(), EmailStatus.FILTERED);
				return;
			}
			Classification c;
			try {
				c = classifier.classify(m);
				run.consecutiveFailures = 0;
			}
			catch (ClassificationException | RuntimeException e) {
				run.consecutiveFailures++;
				throw e;
			}
			run.classified++;
			ProcessOutcome outcome = processor.process(rec, m, c);
			switch (outcome) {
				case CREATED -> run.created++;
				case UPDATED -> run.updated++;
				case REVIEW -> run.newReviewItems++;
				case NOT_PLACEMENT -> {
				}
			}
		}
		catch (Exception e) {
			recordFailure(rec, e, run);
		}
	}

	private void recordFailure(EmailRecord rec, Exception e, Run run) {
		String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
		log.warn("Email {} failed: {}", rec.id(), msg);
		// Outages and bad keys (retryable) never use up attempts; everything else does.
		boolean count = !(e instanceof ClassificationException ce && ce.isRetryable());
		try {
			if (failures.record(rec, msg, count, props.ai().maxAttempts())) {
				run.newReviewItems++;
			}
		}
		catch (RuntimeException inner) {
			log.error("Could not record failure for email {}", rec.id(), inner);
		}
	}

	private static String key(MailAccount account, String messageId) {
		return account.name() + "/" + messageId;
	}

	private static final Comparator<EmailRecord> OLDEST_FIRST = Comparator
			.comparing(EmailRecord::receivedAt, Comparator.nullsLast(Comparator.<Instant>naturalOrder()))
			.thenComparingLong(EmailRecord::id);

	private static final class Run {
		final boolean aiConfigured;
		final Map<String, MailMessage> fetchedMessages = new HashMap<>();
		final Map<MailAccount, SyncStatus> statuses = new EnumMap<>(MailAccount.class);
		int fetched;
		int classified;
		int created;
		int updated;
		int newReviewItems;
		int consecutiveFailures;

		Run(boolean aiConfigured) {
			this.aiConfigured = aiConfigured;
		}

		boolean breakerOpen() {
			return consecutiveFailures >= BREAKER_THRESHOLD;
		}

		SyncReport report(List<Long> actionIds) {
			return new SyncReport(fetched, classified, created, updated, newReviewItems, actionIds,
					Map.copyOf(statuses), aiConfigured);
		}
	}
}
