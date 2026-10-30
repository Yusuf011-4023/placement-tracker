package com.placementtracker.dev;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.mock.env.MockEnvironment;

import com.placementtracker.mail.gmail.GmailMailSource;

/** The seed flag gates {@link DevCommands}; {@link DevSeeder} only writes into an empty database. */
class DevSeedTest {

	private final DevSeeder seeder = mock(DevSeeder.class);

	private final ApplicationContext context = mock(ApplicationContext.class);

	private DevCommands commands(MockEnvironment env) {
		return new DevCommands(mock(GmailMailSource.class), context, env, seeder);
	}

	@Test
	void doesNotSeedWithoutTheFlag() {
		commands(new MockEnvironment()).run();
		verify(seeder, never()).seedIfEmpty();
	}

	@Test
	void doesNotSeedWhenTheFlagIsFalse() {
		commands(new MockEnvironment().withProperty("tracker.dev.seed", "false")).run();
		verify(seeder, never()).seedIfEmpty();
	}

	@Test
	void seedsWhenTheFlagIsTrue() {
		commands(new MockEnvironment().withProperty("tracker.dev.seed", "true")).run();
		verify(seeder).seedIfEmpty();
	}

	@Test
	void flagAloneDoesNotMakeTheAppExit() {
		// The exit path (gmail-smoke) calls SpringApplication.exit(context, ...), which touches the context.
		commands(new MockEnvironment().withProperty("tracker.dev.seed", "true")).run();
		verifyNoInteractions(context);
	}
}
