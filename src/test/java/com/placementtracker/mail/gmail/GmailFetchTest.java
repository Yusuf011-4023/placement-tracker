package com.placementtracker.mail.gmail;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.api.client.http.LowLevelHttpRequest;
import com.google.api.client.http.LowLevelHttpResponse;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.client.testing.http.MockHttpTransport;
import com.google.api.client.testing.http.MockLowLevelHttpRequest;
import com.google.api.client.testing.http.MockLowLevelHttpResponse;
import com.google.api.services.gmail.Gmail;
import com.placementtracker.config.DataDir;
import com.placementtracker.config.TrackerProperties;
import com.placementtracker.mail.MailMessage;

class GmailFetchTest {

	private static final long BASE = Instant.parse("2026-07-01T00:00:00Z").toEpochMilli();

	@TempDir
	Path tmp;

	private final List<String> fetched = new ArrayList<>();

	/** A fake Gmail with m1..mTotal; mN is received N seconds after BASE, and list returns newest first. */
	private Gmail gmail(int total) {
		List<String> newestFirst = IntStream.rangeClosed(1, total).map(i -> total + 1 - i).mapToObj(i -> "m" + i)
				.toList();
		MockHttpTransport transport = new MockHttpTransport() {
			@Override
			public LowLevelHttpRequest buildRequest(String method, String url) {
				return new MockLowLevelHttpRequest(url) {
					@Override
					public LowLevelHttpResponse execute() {
						String path = url.contains("?") ? url.substring(0, url.indexOf('?')) : url;
						String body;
						if (path.endsWith("/messages")) {
							body = "{\"messages\":[" + newestFirst.stream().map(id -> "{\"id\":\"" + id + "\"}")
									.collect(Collectors.joining(",")) + "]}";
						}
						else {
							String id = path.substring(path.lastIndexOf('/') + 1);
							fetched.add(id);
							long ms = BASE + Long.parseLong(id.substring(1)) * 1000;
							body = "{\"id\":\"" + id + "\",\"threadId\":\"t\",\"internalDate\":\"" + ms
									+ "\",\"payload\":{\"headers\":[]}}";
						}
						return new MockLowLevelHttpResponse().setStatusCode(200).setContentType("application/json")
								.setContent(body);
					}
				};
			}
		};
		return new Gmail.Builder(transport, GsonFactory.getDefaultInstance(), null).setApplicationName("t").build();
	}

	private GmailMailSource source(int batchLimit) throws Exception {
		Files.writeString(tmp.resolve("google-client-secret.json"), "{}", StandardCharsets.UTF_8);
		DataDir dir = new DataDir(new TrackerProperties(tmp, null, null, null));
		return new GmailMailSource(dir, flow -> {
		}, new RequestThrottle(1_000_000, System::nanoTime, nanos -> {
		}), batchLimit);
	}

	@Test
	void backfillFetchesOldestFirstInBatches() throws Exception {
		GmailMailSource source = source(500);

		List<MailMessage> batch = source.fetchSince(gmail(1200), Instant.parse("2026-06-01T00:00:00Z"), id -> false);

		assertThat(batch).hasSize(500);
		assertThat(batch.get(0).messageId()).isEqualTo("m1");
		assertThat(batch.get(499).messageId()).isEqualTo("m500");
		assertThat(batch).extracting(MailMessage::receivedAt).isSorted();
		assertThat(fetched).hasSize(500); // bodies fetched for the batch only
		assertThat(source.batchLimit()).isEqualTo(500);
	}

	@Test
	void alreadyStoredIdsAreNotRefetched() throws Exception {
		GmailMailSource source = source(5);
		Set<String> stored = Set.of("m1", "m2", "m3");

		List<MailMessage> batch = source.fetchSince(gmail(10), Instant.parse("2026-06-01T00:00:00Z"), stored::contains);

		// skipped ids never reach messages.get and do not count toward the batch limit
		assertThat(fetched).doesNotContainAnyElementsOf(stored);
		assertThat(batch).extracting(MailMessage::messageId).containsExactly("m4", "m5", "m6", "m7", "m8");
	}
}
