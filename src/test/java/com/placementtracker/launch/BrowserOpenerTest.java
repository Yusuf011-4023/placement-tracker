package com.placementtracker.launch;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

class BrowserOpenerTest {

	private final List<URI> opened = new ArrayList<>();

	private BrowserOpener opener(boolean enabled, String... args) {
		return new BrowserOpener(enabled, new DefaultApplicationArguments(args), opened::add);
	}

	@Test
	void doesNotOpenWhenDisabled() {
		opener(false).openBrowser();
		assertThat(opened).isEmpty();
	}

	@Test
	void doesNotOpenOnAutostart() {
		opener(true, "--autostart").openBrowser();
		assertThat(opened).isEmpty();
	}

	@Test
	void opensWhenEnabledAndInteractive() {
		opener(true).openBrowser();
		assertThat(opened).containsExactly(LaunchHelper.HOME);
	}

	@Test
	void openerFailureIsSwallowed() {
		new BrowserOpener(true, new DefaultApplicationArguments(), uri -> {
			throw new IllegalStateException("no desktop");
		}).openBrowser();
	}
}
