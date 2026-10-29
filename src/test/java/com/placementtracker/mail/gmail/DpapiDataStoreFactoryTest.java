package com.placementtracker.mail.gmail;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import com.google.api.client.auth.oauth2.StoredCredential;
import com.google.api.client.util.store.DataStore;

@EnabledOnOs(OS.WINDOWS)
class DpapiDataStoreFactoryTest {

	@TempDir
	Path dir;

	@Test
	void roundTripsCredentialAndFileIsNotPlaintext() throws Exception {
		String secret = "ya29.super-secret-access-token";
		DataStore<StoredCredential> store = new DpapiDataStoreFactory(dir).getDataStore("StoredCredential");
		store.set("owner", new StoredCredential().setAccessToken(secret).setRefreshToken("1//refresh-secret"));

		DataStore<StoredCredential> reloaded = new DpapiDataStoreFactory(dir).getDataStore("StoredCredential");
		assertThat(reloaded.get("owner").getAccessToken()).isEqualTo(secret);
		assertThat(reloaded.get("owner").getRefreshToken()).isEqualTo("1//refresh-secret");

		try (Stream<Path> files = Files.walk(dir)) {
			Path bin = files.filter(p -> p.toString().endsWith(".bin")).findFirst().orElseThrow();
			String raw = new String(Files.readAllBytes(bin), StandardCharsets.ISO_8859_1);
			assertThat(raw).doesNotContain(secret).doesNotContain("refresh-secret");
		}
	}
}
