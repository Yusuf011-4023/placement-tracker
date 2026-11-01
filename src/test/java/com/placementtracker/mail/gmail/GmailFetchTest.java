package com.placementtracker.mail.gmail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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
import com.placementtracker.mail.MailAuthException;
import com.placementtracker.mail.MailMessage;
import com.placementtracker.mail.MailSourceException;

class GmailFetchTest {

	private static final long BASE = Instant.parse("2026-07-01T00:00:00Z").toEpochMilli();

	@TempDir
	Path tmp;

	private final List<String> fetched = Collections.synchronizedList(new ArrayList<>());
	private final Map<String, Integer> failIds = new ConcurrentHashMap<>();
	private final AtomicInteger inFlight = new AtomicInteger();
	private final AtomicInteger maxInFlight = new AtomicInteger();
	private volatile CountDownLatch gate; // when set, each get waits here (up to 2 s)
	private int failAfter = Integer.MAX_VALUE; // messages.get fails for mN with N above this
	private int failStatus = 500;

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
							Integer idStatus = onGet(id);
							if (idStatus != null) {
								return new MockLowLevelHttpResponse().setStatusCode(idStatus)
										.setContentType("application/json").setContent("{\"error\":{\"code\":" + idStatus + ",\"message\":\"x\"}}");
							}
							if (Integer.parseInt(id.substring(1)) > failAfter) {
								return new MockLowLevelHttpResponse().setStatusCode(failStatus)
										.setContentType("application/json").setContent("{\"error\":{\"code\":" + failStatus + ",\"message\":\"x\"}}");
							}
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
		return source(batchLimit, TrackerProperties.Gmail.DEFAULT_FETCH_THREADS);
	}

	private GmailMailSource source(int batchLimit, int threads) throws Exception {
		Files.writeString(tmp.resolve("google-client-secret.json"), "{}", StandardCharsets.UTF_8);
		DataDir dir = new DataDir(new TrackerProperties(tmp, null, null, null));
		return new GmailMailSource(dir, flow -> {
		}, new RequestThrottle(1_000_000, System::nanoTime, nanos -> {
		}), batchLimit, threads);
	}

	/** Records the get, waits on the gate if one is set, and returns a forced failure status for the id or null. */
	private Integer onGet(String id) {
		fetched.add(id);
		CountDownLatch g = gate;
		if (g != null) {
			int now = inFlight.incrementAndGet();
			maxInFlight.accumulateAndGet(now, Math::max);
			g.countDown();
			try {
				g.await(2, TimeUnit.SECONDS); // opens once 4 gets are inside at the same time
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			finally {
				inFlight.decrementAndGet();
			}
		}
		return failIds.get(id);
	}

	private static final Instant SINCE = Instant.parse("2026-06-01T00:00:00Z");

	@Test
	void fetchesBodiesConcurrently() throws Exception {
		GmailMailSource source = source(100);
		gate = new CountDownLatch(4);
		long start = System.nanoTime();

		List<MailMessage> batch = source.fetchSince(gmail(8), SINCE, id -> false);

		// a sequential fetch never has more than one get in flight, whatever the machine speed
		assertThat(maxInFlight.get()).isGreaterThanOrEqualTo(4);
		assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(15)); // hang guard
		assertThat(batch).extracting(MailMessage::messageId).containsExactly("m1", "m2", "m3", "m4", "m5", "m6", "m7",
				"m8");
	}

	@Test
	void middleFailureKeepsOnlyOlderPrefix() throws Exception {
		GmailMailSource source = source(100);
		failIds.put("m3", 500);

		List<MailMessage> batch = source.fetchSince(gmail(5), SINCE, id -> false);

		assertThat(batch).extracting(MailMessage::messageId).containsExactly("m1", "m2");
	}

	@Test
	void notFoundDoesNotBreakPrefix() throws Exception {
		GmailMailSource source = source(100);
		failIds.put("m2", 404);

		List<MailMessage> batch = source.fetchSince(gmail(5), SINCE, id -> false);

		assertThat(batch).extracting(MailMessage::messageId).containsExactly("m1", "m3", "m4", "m5");
	}

	@Test
	void firstFailureThrows() throws Exception {
		GmailMailSource source = source(100);
		failIds.put("m1", 500);

		assertThatThrownBy(() -> source.fetchSince(gmail(5), SINCE, id -> false))
				.isInstanceOf(MailSourceException.class);
	}

	@Test
	void authFailureAnywhereThrowsAuth() throws Exception {
		GmailMailSource source = source(100);
		failIds.put("m4", 401);

		assertThatThrownBy(() -> source.fetchSince(gmail(5), SINCE, id -> false))
				.isInstanceOf(MailAuthException.class);
	}

	@Test
	void fetchManyReturnsPrefixInOrder() throws Exception {
		GmailMailSource source = source(100);
		failIds.put("m3", 500);

		assertThat(source.fetchAll(gmail(5), List.of("m1", "m2", "m3", "m4"))).extracting(MailMessage::messageId)
				.containsExactly("m1", "m2");
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

	@Test
	void midBatchFailureReturnsFetchedSoFar() throws Exception {
		GmailMailSource source = source(10);
		failAfter = 4; // m5 onwards fail with a 500

		List<MailMessage> batch = source.fetchSince(gmail(20), Instant.parse("2026-06-01T00:00:00Z"), id -> false);

		assertThat(batch).extracting(MailMessage::messageId).containsExactly("m1", "m2", "m3", "m4");
		assertThat(batch.size()).isLessThan(source.batchLimit()); // so the sync loop stops cleanly
	}

	@Test
	void failureBeforeAnyMessageIsStillThrown() throws Exception {
		GmailMailSource source = source(10);
		failAfter = 0;

		assertThatThrownBy(() -> source.fetchSince(gmail(5), Instant.parse("2026-06-01T00:00:00Z"), id -> false))
				.isInstanceOf(MailSourceException.class);
	}

	@Test
	void authFailureMidBatchStillThrows() throws Exception {
		GmailMailSource source = source(10);
		failAfter = 4;
		failStatus = 401;

		assertThatThrownBy(() -> source.fetchSince(gmail(20), Instant.parse("2026-06-01T00:00:00Z"), id -> false))
				.isInstanceOf(MailAuthException.class);
	}

	@Test
	void firstFailureStopsQueuedGets() throws Exception {
		GmailMailSource source = source(100, 2);
		failIds.put("m2", 500);
		gate = new CountDownLatch(2); // m1 and m2 are inside together, so m3.. are still queued

		List<MailMessage> batch = source.fetchAll(gmail(8), List.of("m1", "m2", "m3", "m4", "m5", "m6", "m7", "m8"));

		assertThat(batch).extracting(MailMessage::messageId).containsExactly("m1");
		assertThat(fetched.size()).isLessThan(8);
	}
}
