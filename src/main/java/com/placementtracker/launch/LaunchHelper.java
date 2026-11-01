package com.placementtracker.launch;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Start-up helpers that run before Spring: detect a tracker that is already running, and the autostart flag.
 */
public final class LaunchHelper {

	public static final URI DASHBOARD = URI.create("http://127.0.0.1:8765/api/dashboard");
	public static final URI HOME = URI.create("http://127.0.0.1:8765/");

	static {
		// The JDK client refuses to set Host unless allowed; this must be set before its first use.
		String allowed = System.getProperty("jdk.httpclient.allowRestrictedHeaders", "");
		if (!allowed.toLowerCase().contains("host")) {
			System.setProperty("jdk.httpclient.allowRestrictedHeaders", allowed.isEmpty() ? "host" : allowed + ",host");
		}
	}

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

	/**
	 * True only when {@code dashboard} answers 200 with a JSON object holding the tracker's {@code accounts} and
	 * {@code chart} keys, so a stranger on the port is never mistaken for the tracker. Any failure gives false.
	 */
	public static boolean trackerAlreadyRunning(URI dashboard, Duration timeout) {
		try {
			HttpClient client = HttpClient.newBuilder().connectTimeout(timeout).build();
			// LocalOnlyFilter only accepts 127.0.0.1:8765 / localhost:8765 as the Host, whatever port is probed.
			HttpRequest request = HttpRequest.newBuilder(dashboard).timeout(timeout).header("Host", "127.0.0.1:8765")
					.GET().build();
			HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() != 200) {
				return false;
			}
			JsonNode body = MAPPER.readTree(response.body());
			return body != null && body.isObject() && body.has("accounts") && body.has("chart");
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return false;
		}
		catch (Exception e) {
			return false;
		}
	}
}
