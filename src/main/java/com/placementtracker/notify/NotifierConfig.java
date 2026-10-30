package com.placementtracker.notify;

import java.awt.GraphicsEnvironment;
import java.awt.SystemTray;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class NotifierConfig {

	@Bean
	Notifier notifier() {
		boolean tray = !GraphicsEnvironment.isHeadless() && SystemTray.isSupported();
		return tray ? new TrayNotifier() : new LoggingNotifier();
	}
}
