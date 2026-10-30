package com.placementtracker.mail.gmail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.api.client.auth.oauth2.BearerToken;
import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.auth.oauth2.StoredCredential;
import com.google.api.client.http.GenericUrl;
import com.google.api.client.http.HttpBackOffIOExceptionHandler;
import com.google.api.client.http.HttpRequest;
import com.google.api.client.http.HttpRequestInitializer;
import com.google.api.client.http.HttpResponse;
import com.google.api.client.http.LowLevelHttpRequest;
import com.google.api.client.http.LowLevelHttpResponse;
import com.google.api.client.testing.http.MockHttpTransport;
import com.google.api.client.testing.http.MockLowLevelHttpRequest;
import com.google.api.client.testing.http.MockLowLevelHttpResponse;
import com.placementtracker.config.DataDir;
import com.placementtracker.config.TrackerProperties;
import com.placementtracker.mail.MailAuthException;

class GmailMailSourceTest {

	private static final String SECRET = """
			{"installed":{"client_id":"test.apps.googleusercontent.com","client_secret":"s",
			"auth_uri":"https://accounts.google.com/o/oauth2/auth","token_uri":"https://oauth2.googleapis.com/token",
			"redirect_uris":["http://localhost"]}}
			""";

	@TempDir
	Path tmp;

	private DataDir dataDir;
	private Path tokens;

	@BeforeEach
	void setUp() throws Exception {
		dataDir = new DataDir(new TrackerProperties(tmp, null, null, null));
		Files.writeString(tmp.resolve("google-client-secret.json"), SECRET, StandardCharsets.UTF_8);
		tokens = tmp.resolve("tokens").resolve("gmail");
	}

	private void storeCredential() throws Exception {
		DpapiDataStoreFactory f = new DpapiDataStoreFactory(tokens);
		StoredCredential.getDefaultDataStore(f).set(GmailMailSource.USER,
				new StoredCredential().setAccessToken("old-access").setRefreshToken("dead-refresh"));
	}

	@Test
	void connectDeletesStoredCredentialBeforeAuthorize() throws Exception {
		storeCredential();
		List<Boolean> hadCredential = new ArrayList<>();
		GmailMailSource source = new GmailMailSource(dataDir,
				flow -> hadCredential.add(flow.getCredentialDataStore().containsKey(GmailMailSource.USER)));

		source.connect();

		assertThat(hadCredential).containsExactly(false);
		// the deletion is persisted, so a dead refresh token can never short-circuit the browser sign-in again
		assertThat(StoredCredential.getDefaultDataStore(new DpapiDataStoreFactory(tokens))
				.containsKey(GmailMailSource.USER)).isFalse();
	}

	@Test
	void connectRecoversFromUndecryptableStore() throws Exception {
		Files.createDirectories(tokens);
		Path bin = tokens.resolve("StoredCredential.bin");
		Files.write(bin, "not dpapi at all".getBytes(StandardCharsets.UTF_8));
		List<Boolean> authorized = new ArrayList<>();
		GmailMailSource source = new GmailMailSource(dataDir, flow -> authorized.add(true));

		source.connect();

		assertThat(authorized).containsExactly(true);
		// the store is readable again (empty), and the unreadable original was kept aside rather than lost
		assertThat(StoredCredential.getDefaultDataStore(new DpapiDataStoreFactory(tokens))
				.containsKey(GmailMailSource.USER)).isFalse();
		try (Stream<Path> files = Files.list(tokens)) {
			assertThat(files.map(p -> p.getFileName().toString()))
					.anyMatch(n -> n.startsWith("StoredCredential.bin.unreadable"));
		}
	}

	@Test
	void requestInitializerInstallsBackoff() throws Exception {
		List<Integer> statuses = new ArrayList<>(List.of(429, 503, 200));
		List<String> authHeaders = new ArrayList<>();
		MockHttpTransport transport = new MockHttpTransport() {
			@Override
			public LowLevelHttpRequest buildRequest(String method, String url) {
				return new MockLowLevelHttpRequest(url) {
					@Override
					public LowLevelHttpResponse execute() {
						authHeaders.add(getFirstHeaderValue("Authorization"));
						return new MockLowLevelHttpResponse().setStatusCode(statuses.remove(0)).setContent("{}");
					}
				};
			}
		};
		Credential credential = new Credential(BearerToken.authorizationHeaderAccessMethod())
				.setAccessToken("token-1");
		HttpRequestInitializer init = GmailMailSource.requestInitializer(credential);

		HttpRequest request = transport.createRequestFactory(init)
				.buildGetRequest(new GenericUrl("https://gmail.googleapis.com/gmail/v1/users/me/messages"));
		HttpResponse response = request.execute();

		assertThat(response.getStatusCode()).isEqualTo(200); // 429 and 503 were retried with backoff
		assertThat(authHeaders).hasSize(3).allMatch("Bearer token-1"::equals);
		assertThat(request.getIOExceptionHandler()).isInstanceOf(HttpBackOffIOExceptionHandler.class);
	}

	private static MockHttpTransport sequence(List<Integer> statuses, List<String> bodies, List<Integer> calls) {
		return new MockHttpTransport() {
			@Override
			public LowLevelHttpRequest buildRequest(String method, String url) {
				return new MockLowLevelHttpRequest(url) {
					@Override
					public LowLevelHttpResponse execute() {
						calls.add(1);
						return new MockLowLevelHttpResponse().setStatusCode(statuses.remove(0))
								.setContentType("application/json").setContent(bodies.remove(0));
					}
				};
			}
		};
	}

	private static String error(int code, String reason) {
		return "{\"error\":{\"code\":" + code + ",\"message\":\"m\",\"errors\":[{\"message\":\"m\","
				+ "\"domain\":\"usageLimits\",\"reason\":\"" + reason + "\"}],\"status\":\"X\"}}";
	}

	private static HttpRequest request(MockHttpTransport transport, List<Long> slept) throws Exception {
		Credential credential = new Credential(BearerToken.authorizationHeaderAccessMethod()).setAccessToken("t");
		return transport.createRequestFactory(GmailMailSource.requestInitializer(credential, ms -> slept.add(ms)))
				.buildGetRequest(new GenericUrl("https://gmail.googleapis.com/gmail/v1/users/me/messages/x"));
	}

	@Test
	void rateLimit403IsRetried() throws Exception {
		for (String reason : List.of("rateLimitExceeded", "userRateLimitExceeded")) {
			List<Integer> calls = new ArrayList<>();
			List<Long> slept = new ArrayList<>();
			MockHttpTransport transport = sequence(new ArrayList<>(List.of(403, 200)),
					new ArrayList<>(List.of(error(403, reason), "{}")), calls);

			HttpResponse response = request(transport, slept).execute();

			assertThat(response.getStatusCode()).isEqualTo(200);
			assertThat(calls).hasSize(2);
			assertThat(slept).hasSize(1);
		}
	}

	@Test
	void permission403IsNotRetried() throws Exception {
		List<Integer> calls = new ArrayList<>();
		MockHttpTransport transport = sequence(new ArrayList<>(List.of(403, 200)),
				new ArrayList<>(List.of(error(403, "insufficientPermissions"), "{}")), calls);

		assertThatThrownBy(() -> request(transport, new ArrayList<>()).execute())
				.isInstanceOfSatisfying(com.google.api.client.http.HttpResponseException.class,
						e -> assertThat(e.getStatusCode()).isEqualTo(403));
		assertThat(calls).hasSize(1);
	}

	@Test
	void undecryptableStoreIsAuthError() throws Exception {
		Files.createDirectories(tokens);
		Files.write(tokens.resolve("StoredCredential.bin"), "garbage".getBytes(StandardCharsets.UTF_8));
		GmailMailSource source = new GmailMailSource(dataDir, flow -> {
		});

		assertThat(source.isConnected()).isFalse();
		assertThatThrownBy(() -> source.fetchSince(Instant.parse("2026-08-01T00:00:00Z")))
				.isInstanceOf(MailAuthException.class);
		assertThatThrownBy(() -> source.fetchOne("m1")).isInstanceOf(MailAuthException.class);
	}
}
