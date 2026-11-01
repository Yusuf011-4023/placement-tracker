package com.placementtracker.launch;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class LaunchHelperTest {

	private HttpServer server;

	@AfterEach
	void stop() {
		if (server != null) {
			server.stop(0);
		}
	}

	private URI serve(int status, String body) throws IOException {
		server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
		server.createContext("/api/dashboard", ex -> {
			byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
			ex.sendResponseHeaders(status, bytes.length);
			ex.getResponseBody().write(bytes);
			ex.close();
		});
		server.start();
		return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api/dashboard");
	}

	@Test
	void autostartFlagDetected() {
		assertThat(LaunchHelper.isAutostart(new String[] { "--autostart" })).isTrue();
		assertThat(LaunchHelper.isAutostart(new String[] { "--foo", "--autostart" })).isTrue();
		assertThat(LaunchHelper.isAutostart(new String[] {})).isFalse();
		assertThat(LaunchHelper.isAutostart(new String[] { "--autostarting" })).isFalse();
	}

	@Test
	void runningWhenDashboardAnswersWithTrackerJson() throws IOException {
		URI uri = serve(200, "{\"accounts\":[],\"chart\":[]}");
		assertThat(LaunchHelper.trackerAlreadyRunning(uri, Duration.ofSeconds(2))).isTrue();
	}

	@Test
	void notRunningWhenSomethingElseAnswers() throws IOException {
		URI uri = serve(200, "<html>hello</html>");
		assertThat(LaunchHelper.trackerAlreadyRunning(uri, Duration.ofSeconds(2))).isFalse();
	}

	@Test
	void notRunningWhenJsonLacksTrackerKeys() throws IOException {
		URI uri = serve(200, "{\"accounts\":[]}");
		assertThat(LaunchHelper.trackerAlreadyRunning(uri, Duration.ofSeconds(2))).isFalse();
	}

	@Test
	void notRunningOnNon200() throws IOException {
		URI uri = serve(500, "{\"accounts\":[],\"chart\":[]}");
		assertThat(LaunchHelper.trackerAlreadyRunning(uri, Duration.ofSeconds(2))).isFalse();
	}

	@Test
	void notRunningWhenNothingListens() throws IOException {
		int port;
		try (ServerSocket s = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
			port = s.getLocalPort();
		}
		long start = System.nanoTime();
		boolean running = LaunchHelper.trackerAlreadyRunning(URI.create("http://127.0.0.1:" + port + "/api/dashboard"),
				Duration.ofSeconds(1));
		assertThat(running).isFalse();
		assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(3));
	}

	@Test
	void notRunningWhenServerStallsAfterHeaders() throws Exception {
		java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
		server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
		server.createContext("/api/dashboard", ex -> {
			try {
				ex.sendResponseHeaders(200, 100);
				ex.getResponseBody().flush();
				release.await(10, java.util.concurrent.TimeUnit.SECONDS);
			}
			catch (InterruptedException | IOException ignored) {
			}
			finally {
				ex.close();
			}
		});
		server.start();
		URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api/dashboard");
		long start = System.nanoTime();
		try {
			assertThat(LaunchHelper.trackerAlreadyRunning(uri, Duration.ofMillis(300))).isFalse();
			assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(1500));
		}
		finally {
			release.countDown();
		}
	}
}
