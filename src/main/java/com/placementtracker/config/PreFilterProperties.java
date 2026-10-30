package com.placementtracker.config;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Pre-filter word and domain lists under the {@code tracker.prefilter.*} prefix. */
@ConfigurationProperties("tracker.prefilter")
public record PreFilterProperties(List<String> keywords, List<String> atsDomains, List<String> extraDomains) {

	public PreFilterProperties {
		keywords = keywords == null ? List.of() : List.copyOf(keywords);
		atsDomains = atsDomains == null ? List.of() : List.copyOf(atsDomains);
		extraDomains = extraDomains == null ? List.of() : List.copyOf(extraDomains);
	}
}
