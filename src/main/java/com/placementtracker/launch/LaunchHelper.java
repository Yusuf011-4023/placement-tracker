package com.placementtracker.launch;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Start-up helpers that run before Spring: detect a tracker that is already running, and the autostart flag.
 */
public final class LaunchHelper {

	public static final URI DASHBOARD = URI.create("http://127.0.0.1:8765/api/dashboard");
	public static final URI HOME = URI.create("http://127.0.0.1:8765/");

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private LaunchHelper() {
	}

	/** True when the app was started by the login auto-start entry. */
	public static boolean isAutostart(String[] args) {
		if (args == null) {
			return false;
		}
		for (String a : args) {
			if ("--autostart".equals(a)) {
				return true;
			}
		}
		return false;
	}

	/** Largest response body read while probing; the tracker's dashboard JSON is far smaller than a stranger's page. */
	private static final int MAX_BODY_BYTES = 64 * 1024;

	/**
	 * True only when {@code dashboard} answers 200 with a JSON object holding the tracker's {@code accounts} and
	 * {@code chart} keys, so a stranger on the port is never mistaken for the tracker. The whole probe (connect,
	 * headers and body) is bounded by {@code timeout}, and any failure gives false. For the real port the JDK client
	 * sends {@code Host: 127.0.0.1:8765} itself, which is what LocalOnlyFilter accepts.
	 */
	public static boolean trackerAlreadyRunning(URI dashboard, Duration timeout) {
		try (HttpClient client = HttpClient.newBuilder().connectTimeout(timeout).build()) {
			HttpRequest request = HttpRequest.newBuilder(dashboard).timeout(timeout).GET().build();
			CompletableFuture<Boolean> probe = client.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream())
					.thenApplyAsync(LaunchHelper::looksLikeTracker);
			try {
				return probe.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
			}
			finally {
				probe.cancel(true);
				client.shutdownNow(); // releases a stalled read so close() does not wait for it
			}
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return false;
		}
		catch (Exception e) {
			return false;
		}
	}

	private static boolean looksLikeTracker(HttpResponse<InputStream> response) {
		try (InputStream in = response.body()) {
			if (response.statusCode() != 200) {
				return false;
			}
			JsonNode body = MAPPER.readTree(in.readNBytes(MAX_BODY_BYTES));
			return body != null && body.isObject() && body.has("accounts") && body.has("chart");
		}
		catch (Exception e) {
			return false;
		}
	}
}
