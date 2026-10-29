package com.placementtracker.mail.gmail;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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

	private static final Logger log = LoggerFactory.getLogger(GmailMailSource.class);

	private static final String APPLICATION_NAME = "Placement Tracker";

	private final JsonFactory json = GsonFactory.getDefaultInstance();

	private final Path secretFile;

	private final DpapiDataStoreFactory dataStoreFactory;

	private final Authorizer authorizer;

	private volatile HttpTransport transport;

	/** How long the browser sign-in may take before the local callback server gives up. */
	static final Duration SIGN_IN_TIMEOUT = Duration.ofMinutes(5);

	@Autowired
	public GmailMailSource(DataDir dataDir) {
		this(dataDir, GmailMailSource::browserSignIn);
	}

	/**
	 * Opens the browser sign-in. The receiver otherwise waits for Google's callback forever, which would leave a
	 * single-flight connect stuck after the owner closes the tab; so it is stopped after {@link #SIGN_IN_TIMEOUT}.
	 */
	private static void browserSignIn(GoogleAuthorizationCodeFlow flow) throws IOException {
		LocalServerReceiver receiver = new LocalServerReceiver();
		AtomicBoolean timedOut = new AtomicBoolean();
		CompletableFuture<Void> timer = CompletableFuture.runAsync(() -> {
			timedOut.set(true);
			try {
				receiver.stop();
			}
			catch (IOException e) {
				log.debug("Stopping the sign-in receiver failed", e);
			}
		}, CompletableFuture.delayedExecutor(SIGN_IN_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS));
		try {
			new AuthorizationCodeInstalledApp(flow, receiver).authorize(USER);
		}
		catch (IOException | RuntimeException e) {
			if (timedOut.get()) {
				throw new IOException("the Google sign-in was not finished within " + SIGN_IN_TIMEOUT.toMinutes()
						+ " minutes; press Connect to try again", e);
			}
			throw e;
		}
		finally {
			timer.cancel(false);
		}
	}

	/** Test seam: {@code authorizer} replaces the browser sign-in. */
	GmailMailSource(DataDir dataDir, Authorizer authorizer) {
		this.secretFile = dataDir.resolve("google-client-secret.json");
		this.dataStoreFactory = new DpapiDataStoreFactory(dataDir.resolve("tokens").resolve("gmail"));
		this.authorizer = authorizer;
	}

	/** Runs the interactive OAuth sign-in for {@link #USER} on the given flow. */
	@FunctionalInterface
	interface Authorizer {
		void authorize(GoogleAuthorizationCodeFlow flow) throws IOException;
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
	public Optional<String> setupProblem() {
		if (Files.isRegularFile(secretFile)) {
			return Optional.empty();
		}
		return Optional.of("Put your Google client secret at " + secretFile + ". Google's download is named "
				+ "client_secret_<something>.json: rename it to google-client-secret.json (see README).");
	}

	@Override
	public void connect() throws MailAuthException {
		try {
			GoogleAuthorizationCodeFlow flow;
			try {
				flow = flow();
			}
			catch (DpapiDataStoreFactory.UnreadableStoreException e) {
				log.warn("Gmail token store is unreadable ({}); moving it aside and signing in afresh", e.getMessage());
				dataStoreFactory.discardUnreadable(StoredCredential.DEFAULT_DATA_STORE_ID);
				flow = flow();
			}
			// A stored credential (even one whose refresh token Google has revoked) makes authorize() return
			// without opening the browser, so Reconnect must drop it first.
			flow.getCredentialDataStore().delete(USER);
			authorizer.authorize(flow);
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
		boolean auth = e instanceof TokenResponseException || e instanceof DpapiDataStoreFactory.UnreadableStoreException
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
