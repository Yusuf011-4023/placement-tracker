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

import com.google.api.client.auth.oauth2.StoredCredential;
import com.google.api.client.http.HttpRequest;
import com.google.api.client.http.HttpRequestInitializer;
import com.google.api.client.http.javanet.NetHttpTransport;
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
		dataDir = new DataDir(new TrackerProperties(tmp, null, null));
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
