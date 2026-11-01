package com.placementtracker.launch;

import java.awt.Desktop;
import java.net.URI;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Opens the tracker page once the app is up, unless disabled or started silently at login. */
@Component
public class BrowserOpener {

	private static final Logger log = LoggerFactory.getLogger(BrowserOpener.class);

	private final boolean enabled;
	private final boolean autostart;
	private final Consumer<URI> opener;

	@Autowired
	public BrowserOpener(@Value("${tracker.open-browser:true}") boolean enabled, ApplicationArguments args) {
		this(enabled, args, BrowserOpener::browse);
	}

	BrowserOpener(boolean enabled, ApplicationArguments args, Consumer<URI> opener) {
		this.enabled = enabled;
		this.autostart = args.containsOption("autostart");
		this.opener = opener;
	}

	@EventListener(ApplicationReadyEvent.class)
	public void openBrowser() {
		if (!enabled || autostart) {
			return;
		}
		try {
			opener.accept(LaunchHelper.HOME);
		}
		catch (Exception e) {
			log.warn("Could not open the tracker: {}", e.getMessage());
		}
	}

	/** Opens {@code uri} in the default browser; does nothing when the desktop cannot browse. */
	public static void browse(URI uri) {
		try {
			if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
				Desktop.getDesktop().browse(uri);
			}
		}
		catch (Exception e) {
			log.warn("Could not open the tracker: {}", e.getMessage());
		}
	}
}
