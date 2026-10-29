package com.placementtracker.mail.gmail;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.auth.oauth2.StoredCredential;
import com.google.api.client.auth.oauth2.TokenResponseException;
import com.google.api.client.extensions.java6.auth.oauth2.AuthorizationCodeInstalledApp;
import com.google.api.client.extensions.jetty.auth.oauth2.LocalServerReceiver;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.HttpResponseException;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.gmail.Gmail;
import com.google.api.services.gmail.GmailScopes;
import com.google.api.services.gmail.model.ListMessagesResponse;
import com.google.api.services.gmail.model.Message;
import com.placementtracker.config.DataDir;
import com.placementtracker.mail.MailAccount;
import com.placementtracker.mail.MailAuthException;
import com.placementtracker.mail.MailMessage;
import com.placementtracker.mail.MailSource;
import com.placementtracker.mail.MailSourceException;

/**
 * Read-only Gmail source. Only {@link #connect()} may open a browser; fetches use the stored credential and
 * throw {@link MailAuthException} when it is missing or can no longer be refreshed.
 */
@Component
public class GmailMailSource implements MailSource {

	static final String USER = "owner";

	private static final String APPLICATION_NAME = "Placement Tracker";

	private final JsonFactory json = GsonFactory.getDefaultInstance();

	private final Path secretFile;

	private final DpapiDataStoreFactory dataStoreFactory;

	private volatile HttpTransport transport;

	public GmailMailSource(DataDir dataDir) {
		this.secretFile = dataDir.resolve("google-client-secret.json");
		this.dataStoreFactory = new DpapiDataStoreFactory(dataDir.resolve("tokens").resolve("gmail"));
	}

	@Override
	public MailAccount account() {
		return MailAccount.GMAIL;
	}

	@Override
	public boolean isConnected() {
		if (!Files.isRegularFile(secretFile)) {
			return false;
		}
		try {
			return dataStoreFactory.getDataStore(StoredCredential.DEFAULT_DATA_STORE_ID).containsKey(USER);
		}
		catch (IOException | RuntimeException e) {
			return false;
		}
	}

	@Override
	public void connect() throws MailAuthException {
		try {
			GoogleAuthorizationCodeFlow flow = flow();
			new AuthorizationCodeInstalledApp(flow, new LocalServerReceiver()).authorize(USER);
		}
		catch (IOException | GeneralSecurityException e) {
			throw new MailAuthException("Gmail sign-in failed: " + e.getMessage(), e);
		}
	}

	@Override
	public List<MailMessage> fetchSince(Instant since) throws MailAuthException, MailSourceException {
		Gmail gmail = gmail();
		String query = "after:" + since.getEpochSecond() + " -in:sent -in:drafts -in:trash";
		List<MailMessage> out = new ArrayList<>();
		try {
			String pageToken = null;
			do {
				ListMessagesResponse page = gmail.users().messages().list("me").setQ(query)
						.setIncludeSpamTrash(true).setMaxResults(100L).setPageToken(pageToken).execute();
				if (page.getMessages() != null) {
					for (Message ref : page.getMessages()) {
						try {
							out.add(GmailMessageMapper.toMailMessage(get(gmail, ref.getId())));
						}
						catch (HttpResponseException e) {
							if (e.getStatusCode() != 404) { // 404: deleted between list and get
								throw e;
							}
						}
					}
				}
				pageToken = page.getNextPageToken();
			}
			while (pageToken != null);
		}
		catch (IOException e) {
			throw translate(e);
		}
		out.sort(Comparator.comparing(MailMessage::receivedAt));
		return out;
	}

	@Override
	public Optional<MailMessage> fetchOne(String messageId) throws MailAuthException, MailSourceException {
		Gmail gmail = gmail();
		try {
			return Optional.of(GmailMessageMapper.toMailMessage(get(gmail, messageId)));
		}
		catch (HttpResponseException e) {
			if (e.getStatusCode() == 404) {
				return Optional.empty();
			}
			throw translate(e);
		}
		catch (IOException e) {
			throw translate(e);
		}
	}

	private static Message get(Gmail gmail, String id) throws IOException {
		return gmail.users().messages().get("me", id).setFormat("full").execute();
	}

	/** Refresh/authorization failures become auth errors, everything else a source error. */
	private static MailSourceException translate(IOException e) throws MailAuthException {
		boolean auth = e instanceof TokenResponseException
				|| (e instanceof HttpResponseException h && h.getStatusCode() == 401)
				|| (e.getMessage() != null && e.getMessage().contains("invalid_grant"));
		if (auth) {
			throw new MailAuthException("Gmail authorization failed - reconnect Gmail: " + e.getMessage(), e);
		}
		return new MailSourceException("Gmail request failed: " + e.getMessage(), e);
	}

	private Gmail gmail() throws MailAuthException, MailSourceException {
		if (!Files.isRegularFile(secretFile)) {
			throw new MailAuthException("Gmail is not set up: missing " + secretFile, null);
		}
		try {
			Credential credential = flow().loadCredential(USER);
			if (credential == null) {
				throw new MailAuthException("Gmail is not connected - connect Gmail first", null);
			}
			return new Gmail.Builder(transport(), json, credential).setApplicationName(APPLICATION_NAME).build();
		}
		catch (GeneralSecurityException e) {
			throw new MailSourceException("Cannot set up Gmail transport: " + e.getMessage(), e);
		}
		catch (IOException e) {
			throw translate(e);
		}
	}

	private HttpTransport transport() throws GeneralSecurityException, IOException {
		HttpTransport t = transport;
		if (t == null) {
			t = GoogleNetHttpTransport.newTrustedTransport();
			transport = t;
		}
		return t;
	}

	private GoogleAuthorizationCodeFlow flow() throws IOException, GeneralSecurityException, MailAuthException {
		if (!Files.isRegularFile(secretFile)) {
			throw new MailAuthException("Gmail is not set up: missing client secret file " + secretFile, null);
		}
		GoogleClientSecrets secrets;
		try (Reader reader = Files.newBufferedReader(secretFile, StandardCharsets.UTF_8)) {
			secrets = GoogleClientSecrets.load(json, reader);
		}
		return new GoogleAuthorizationCodeFlow.Builder(transport(), json, secrets,
				Set.of(GmailScopes.GMAIL_READONLY)).setDataStoreFactory(dataStoreFactory).setAccessType("offline")
						.build();
	}
}
