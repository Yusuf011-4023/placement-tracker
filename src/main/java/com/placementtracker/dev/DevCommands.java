package com.placementtracker.dev;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import com.placementtracker.mail.MailMessage;
import com.placementtracker.mail.gmail.GmailMailSource;

/**
 * Hidden developer-only flags. Each flag does nothing unless its property is set to true. Later tasks add more
 * flags here. Currently: {@code tracker.dev.gmail-smoke} (fetch recent mail, then exit) and {@code tracker.dev.seed}
 * (fill an empty database with sample applications, then keep running).
 */
@Component
public class DevCommands implements CommandLineRunner {

	private static final Logger log = LoggerFactory.getLogger(DevCommands.class);

	private final GmailMailSource gmail;

	private final ApplicationContext context;

	private final Environment env;

	private final DevSeeder seeder;

	public DevCommands(GmailMailSource gmail, ApplicationContext context, Environment env, DevSeeder seeder) {
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
