package com.placementtracker.classify;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.placementtracker.config.DataDir;

@Configuration
public class AnthropicConfig {

	private static final Logger log = LoggerFactory.getLogger(AnthropicConfig.class);

	/** Returns null (a NullBean) when no key is configured; the classifier then reports isConfigured() == false. */
	@Bean
	public AnthropicClient anthropicClient(DataDir dataDir) {
		String key = resolveApiKey(System.getenv("ANTHROPIC_API_KEY"), dataDir.resolve("config.properties"));
		if (key == null) {
			log.warn("No Anthropic API key (env ANTHROPIC_API_KEY or anthropic.api-key in config.properties); "
					+ "email classification is disabled until one is set");
			return null;
		}
		return AnthropicOkHttpClient.builder().apiKey(key).build();
	}

	static String resolveApiKey(String envValue, Path configFile) {
		if (envValue != null && !envValue.isBlank()) {
			return envValue.trim();
		}
		if (Files.isRegularFile(configFile)) {
			Properties p = new Properties();
			try (Reader r = Files.newBufferedReader(configFile)) {
				p.load(r);
			}
			catch (IOException e) {
				log.warn("Cannot read {}: {}", configFile, e.getMessage());
				return null;
			}
			String v = p.getProperty("anthropic.api-key");
			if (v != null && !v.isBlank()) {
				return v.trim();
			}
		}
		return null;
	}
}
