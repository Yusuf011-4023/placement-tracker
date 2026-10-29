package com.placementtracker.dev;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.mock.env.MockEnvironment;

import com.placementtracker.mail.gmail.GmailMailSource;

/** The seed flag gates {@link DevCommands}; {@link DevSeeder} only writes into an empty database. */
class DevSeedTest {

	private final DevSeeder seeder = mock(DevSeeder.class);

	private DevCommands commands(MockEnvironment env) {
		return new DevCommands(mock(GmailMailSource.class), mock(ApplicationContext.class), env, seeder);
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
		// run() would call System.exit if it treated the flag like gmail-smoke; reaching the assertion proves it did not.
		commands(new MockEnvironment().withProperty("tracker.dev.seed", "true")).run();
		assertThat(true).isTrue();
	}
}
