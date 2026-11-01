package com.placementtracker.dev;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import com.placementtracker.mail.EmailRecord;
import com.placementtracker.mail.EmailRepository;
import com.placementtracker.mail.EmailStatus;
import com.placementtracker.mail.MailMessage;
import com.placementtracker.mail.PreFilter;
import com.placementtracker.mail.gmail.GmailMailSource;

/**
 * Hidden developer-only flags. Each flag does nothing unless its property is set to true. Later tasks add more
 * flags here. Currently: {@code tracker.dev.gmail-smoke} (fetch recent mail, then exit), {@code tracker.dev.seed}
 * (fill an empty database with sample applications, then keep running) and {@code tracker.dev.prefilter-replay}
 * (read-only: re-check stored emails against the current pre-filter, then exit).
 */
@Component
public class DevCommands implements CommandLineRunner {

	private static final Logger log = LoggerFactory.getLogger(DevCommands.class);

	private static final int REPLAY_CHUNK = 500;

	private final GmailMailSource gmail;

	private final ApplicationContext context;

	private final Environment env;

	private final DevSeeder seeder;

	private final EmailRepository emails;

	private final PreFilter preFilter;

	public DevCommands(GmailMailSource gmail, ApplicationContext context, Environment env, DevSeeder seeder,
			EmailRepository emails, PreFilter preFilter) {
		this.emails = emails;
		this.preFilter = preFilter;
		this.gmail = gmail;
		this.context = context;
		this.env = env;
		this.seeder = seeder;
	}

	@Override
	public void run(String... args) {
		if (env.getProperty("tracker.dev.seed", Boolean.class, false)) {
			seeder.seedIfEmpty();
		}
		if (env.getProperty("tracker.dev.gmail-smoke", Boolean.class, false)) {
			int code = 0;
			try {
				gmailSmoke();
			}
			catch (Exception e) {
				log.error("gmail-smoke failed: {}", e.getMessage());
				code = 1;
			}
			int exit = code;
			System.exit(SpringApplication.exit(context, () -> exit));
		}
		if (env.getProperty("tracker.dev.prefilter-replay", Boolean.class, false)) {
			int code = 0;
			if (env.getProperty("tracker.sync.scheduler-enabled", Boolean.class, true)) {
				log.error("prefilter-replay refused: start it with --tracker.sync.scheduler-enabled=false "
						+ "(and --server.port=0) so it does not clash with the running tracker or start a sync");
				code = 1;
			}
			else {
				try {
					prefilterReplay();
				}
				catch (Exception e) {
					log.error("prefilter-replay failed: {}", e.getMessage());
					code = 1;
				}
			}
			int exit = code;
			System.exit(SpringApplication.exit(context, () -> exit));
		}
	}

	/** Read-only: re-runs the current pre-filter over stored emails and reports which would no longer reach the AI. */
	private void prefilterReplay() throws Exception {
		List<EmailRecord> records = emails.findForPrefilterReplay();
		Map<String, EmailRecord> byMessageId = new HashMap<>();
		for (EmailRecord r : records) {
			byMessageId.put(r.messageId(), r);
		}
		List<String> pending = new ArrayList<>(byMessageId.keySet());
		int replayed = 0;
		int kept = 0;
		int dropped = 0;
		List<EmailRecord> relevantDropped = new ArrayList<>();
		while (!pending.isEmpty()) {
			List<String> remaining = new ArrayList<>();
			boolean progress = false;
			for (int i = 0; i < pending.size(); i += REPLAY_CHUNK) {
				List<String> chunk = pending.subList(i, Math.min(i + REPLAY_CHUNK, pending.size()));
				Set<String> got = new HashSet<>();
				for (MailMessage m : gmail.fetchMany(chunk)) {
					got.add(m.messageId());
					replayed++;
					progress = true;
					if (preFilter.shouldClassify(m)) {
						kept++;
					}
					else {
						dropped++;
						EmailRecord r = byMessageId.get(m.messageId());
						if (r != null && isRelevant(r)) {
							relevantDropped.add(r);
						}
					}
				}
				for (String id : chunk) {
					if (!got.contains(id)) {
						remaining.add(id);
					}
				}
			}
			if (!progress) {
				break;
			}
			pending = remaining;
		}
		log.info("prefilter-replay: {} replayed; {} still sent to AI, {} now filtered; relevant "
				+ "(PROCESSED/REVIEW/linked) dropped: {}", replayed, kept, dropped, relevantDropped.size());
		for (EmailRecord r : relevantDropped) {
			log.info("prefilter-replay relevant dropped: id={} from={} subject={} date={}", r.id(), r.from(),
					r.subject(), r.receivedAt());
		}
		log.info("prefilter-replay: {} could not be fetched (deleted from Gmail or failed)", pending.size());
	}

	private static boolean isRelevant(EmailRecord r) {
		return r.status() == EmailStatus.PROCESSED || r.status() == EmailStatus.REVIEW || r.applicationId() != null;
	}

	private void gmailSmoke() throws Exception {
		if (!gmail.isConnected()) {
			log.info("Gmail not connected; starting sign-in (a browser window will open)");
			gmail.connect();
		}
		List<MailMessage> messages = gmail.fetchSince(Instant.now().minus(Duration.ofDays(2)));
		log.info("gmail-smoke: {} messages in the last 2 days", messages.size());
		for (MailMessage m : messages) {
			log.info("gmail-smoke subject: {}", m.subject());
		}
	}
}
