package com.placementtracker.sync;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumMap;
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
import com.placementtracker.review.ReviewReason;
import com.placementtracker.review.ReviewRepository;

/**
 * Pulls mail from every connected source, de-duplicates and pre-filters it, classifies what is left and applies the
 * result. Nothing is skipped permanently: the high-water mark moves only after a full listing succeeds, and emails
 * that could not be classified stay in the database (FAILED or PENDING) and are retried on every pass.
 */
@Service
public class SyncService {

	private static final Logger log = LoggerFactory.getLogger(SyncService.class);

	private final List<MailSource> sources;
	private final PreFilter preFilter;
	private final EmailClassifier classifier;
	private final EmailProcessor processor;
	private final EmailRepository emails;
	private final SyncStateRepository syncState;
	private final ReviewRepository reviews;
	private final TrackerProperties props;

	/** Spring constructor: tolerates there being no MailSource beans yet. */
	@Autowired
	public SyncService(ObjectProvider<MailSource> sources, PreFilter preFilter, EmailClassifier classifier,
			EmailProcessor processor, EmailRepository emails, SyncStateRepository syncState, ReviewRepository reviews,
			TrackerProperties props) {
		this(sources.orderedStream().toList(), preFilter, classifier, processor, emails, syncState, reviews, props);
	}

	public SyncService(List<MailSource> sources, PreFilter preFilter, EmailClassifier classifier,
			EmailProcessor processor, EmailRepository emails, SyncStateRepository syncState, ReviewRepository reviews,
			TrackerProperties props) {
		this.sources = List.copyOf(sources);
		this.preFilter = preFilter;
		this.classifier = classifier;
		this.processor = processor;
		this.emails = emails;
		this.syncState = syncState;
		this.reviews = reviews;
		this.props = props;
	}

	/** Runs one pass. A concurrent caller waits for the running pass, then runs its own (everything is idempotent). */
	public synchronized SyncReport syncAll() {
		Run run = new Run(classifier.isConfigured());
		for (MailSource source : sources) {
			try {
				syncSource(source, run);
			}
			catch (RuntimeException e) {
				log.error("Sync of {} failed unexpectedly", source.account(), e);
				syncState.markError(source.account(), SyncStatus.ERROR, String.valueOf(e.getMessage()));
				run.statuses.put(source.account(), SyncStatus.ERROR);
			}
		}
		try {
			retryPass(run);
		}
		catch (RuntimeException e) {
			log.error("Retry pass failed", e);
		}
		return run.report(processor.newActionIds());
	}

	private void syncSource(MailSource source, Run run) {
		MailAccount account = source.account();
		if (!source.isConnected()) {
			run.statuses.put(account, SyncStatus.NOT_CONNECTED);
			return;
		}
		Instant previous = syncState.get(account).highWater();
		Instant since = previous == null ? props.sync().backfillFrom() : previous.minus(1, ChronoUnit.DAYS);
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
			if (m.receivedAt() != null && (newest == null || m.receivedAt().isAfter(newest))) {
				newest = m.receivedAt();
			}
			Optional<EmailRecord> rec = emails.insertIfNew(m, DedupeKeys.of(m));
			if (rec.isPresent()) {
				handle(rec.get(), m, run);
			}
		}
		syncState.markSuccess(account, newest == null ? props.sync().backfillFrom() : newest, Instant.now());
		run.statuses.put(account, SyncStatus.OK);
	}

	/** Dedupe, pre-filter, classify, apply. Never throws for a per-email problem. */
	private void handle(EmailRecord rec, MailMessage m, Run run) {
		run.handled.add(rec.id());
		try {
			if (emails.existsDedupeKeyBefore(rec.dedupeKey(), rec.id())) {
				emails.setStatus(rec.id(), EmailStatus.DUPLICATE);
				return;
			}
			if (!preFilter.shouldClassify(m)) {
				emails.setStatus(rec.id(), EmailStatus.FILTERED);
				return;
			}
			if (!run.aiConfigured) {
				return; // stays PENDING, no attempt burned; picked up once a key exists
			}
			Classification c = classifier.classify(m);
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
		try {
			int attempts = emails.recordFailure(rec.id(), msg);
			if (attempts >= props.ai().maxAttempts()) {
				reviews.open(rec.id(), rec.applicationId(), ReviewReason.AI_FAILED, "{}");
				emails.setStatus(rec.id(), EmailStatus.REVIEW);
				run.newReviewItems++;
			}
			else {
				emails.setStatus(rec.id(), EmailStatus.FAILED);
			}
		}
		catch (RuntimeException inner) {
			log.error("Could not record failure for email {}", rec.id(), inner);
		}
	}

	/** Retries FAILED emails under the attempt cap and anything left PENDING, re-fetching each by message id. */
	private void retryPass(Run run) {
		if (!run.aiConfigured) {
			return;
		}
		List<EmailRecord> todo = new ArrayList<>(emails.findRetryable(props.ai().maxAttempts()));
		todo.addAll(emails.findPending());
		Set<Long> seen = new HashSet<>();
		for (EmailRecord rec : todo) {
			if (run.handled.contains(rec.id()) || !seen.add(rec.id())) {
				continue;
			}
			MailSource source = sources.stream().filter(s -> s.account() == rec.account()).findFirst().orElse(null);
			if (source == null || !source.isConnected()) {
				continue;
			}
			Optional<MailMessage> m;
			try {
				m = source.fetchOne(rec.messageId());
			}
			catch (MailAuthException | MailSourceException | RuntimeException e) {
				log.warn("Could not re-fetch {} for retry: {}", rec.messageId(), e.getMessage());
				continue;
			}
			if (m.isEmpty()) {
				log.warn("Message {} ({}) no longer exists; marking FILTERED", rec.messageId(), rec.account());
				emails.setStatus(rec.id(), EmailStatus.FILTERED);
				continue;
			}
			handle(rec, m.get(), run);
		}
	}

	private static final class Run {
		final boolean aiConfigured;
		final Set<Long> handled = new HashSet<>();
		final Map<MailAccount, SyncStatus> statuses = new EnumMap<>(MailAccount.class);
		int fetched;
		int classified;
		int created;
		int updated;
		int newReviewItems;

		Run(boolean aiConfigured) {
			this.aiConfigured = aiConfigured;
		}

		SyncReport report(List<Long> actionIds) {
			return new SyncReport(fetched, classified, created, updated, newReviewItems, actionIds,
					Map.copyOf(statuses), aiConfigured);
		}
	}
}
