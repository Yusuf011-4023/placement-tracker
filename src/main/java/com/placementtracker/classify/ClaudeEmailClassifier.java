package com.placementtracker.classify;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.anthropic.client.AnthropicClient;
import com.anthropic.errors.AnthropicException;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicRetryableException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.errors.InternalServerException;
import com.anthropic.errors.RateLimitException;
import com.anthropic.models.ErrorType;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.StructuredMessage;
import com.anthropic.models.messages.StructuredMessageCreateParams;
import com.placementtracker.config.TrackerProperties;
import com.placementtracker.mail.MailMessage;

@Component
public class ClaudeEmailClassifier implements EmailClassifier {

	static final int MAX_BODY_CHARS = 20_000;
	private static final long MAX_TOKENS = 4096L;

	/** Seam over the SDK call so request building and response mapping can be unit-tested. */
	@FunctionalInterface
	interface ClaudeCall {
		StructuredMessage<Classification> send(StructuredMessageCreateParams<Classification> params);
	}

	private final boolean configured;
	private final ClaudeCall call;
	private final TrackerProperties props;
	private final String systemPrompt;

	@Autowired
	public ClaudeEmailClassifier(ObjectProvider<AnthropicClient> clients, TrackerProperties props) {
		this(clients.getIfAvailable(), props);
	}

	private ClaudeEmailClassifier(AnthropicClient client, TrackerProperties props) {
		this(client == null ? null : (ClaudeCall) (p -> client.messages().create(p)), props, client != null);
	}

	ClaudeEmailClassifier(ClaudeCall call, TrackerProperties props) {
		this(call, props, true);
	}

	ClaudeEmailClassifier(ClaudeCall call, TrackerProperties props, boolean configured) {
		this.configured = configured;
		this.call = call;
		this.props = props;
		this.systemPrompt = loadPrompt();
	}

	@Override
	public boolean isConfigured() {
		return configured;
	}

	@Override
	public Classification classify(MailMessage m) throws ClassificationException {
		if (!configured) {
			throw new ClassificationException("Anthropic API key not configured", true);
		}
		StructuredMessageCreateParams<Classification> params = MessageCreateParams.builder()
				.model(props.ai().model())
				.maxTokens(MAX_TOKENS)
				.system(systemPrompt)
				.outputConfig(Classification.class)
				.addUserMessage(renderEmail(m))
				.build();
		StructuredMessage<Classification> response;
		try {
			response = call.send(params);
		}
		catch (com.anthropic.errors.UnauthorizedException | com.anthropic.errors.PermissionDeniedException e) {
			// A bad or revoked key: the owner fixes it by updating the key, so keep the email waiting.
			throw new ClassificationException("Anthropic API key rejected: " + e.getMessage(), e, true);
		}
		catch (RateLimitException | InternalServerException e) {
			throw new ClassificationException(
					"Anthropic API error " + e.statusCode() + ": " + e.getMessage(), e, true);
		}
		catch (AnthropicServiceException e) {
			// 402 billing_error (the owner tops up credit) and 408 timeout have no dedicated SDK class. Neither
			// says anything about the email, so they must not use up its attempts.
			int status = e.statusCode();
			boolean billing = status == 402
					|| e.errorType().map(t -> ErrorType.BILLING_ERROR.equals(t)).orElse(false);
			boolean retryable = billing || status == 408 || status == 429 || status >= 500;
			String what = billing ? "Anthropic billing problem (check your credit balance)" : "Anthropic API error";
			throw new ClassificationException(what + " " + status + ": " + e.getMessage(), e, retryable);
		}
		catch (AnthropicIoException | AnthropicRetryableException e) {
			throw new ClassificationException("Could not reach the Anthropic API: " + e.getMessage(), e, true);
		}
		catch (AnthropicException e) {
			throw new ClassificationException("Anthropic client error: " + e.getMessage(), e, false);
		}
		StopReason stop = response.stopReason().orElse(null);
		if (StopReason.REFUSAL.equals(stop)) {
			throw new ClassificationException("Claude refused to classify the email", false);
		}
		if (StopReason.MAX_TOKENS.equals(stop)) {
			throw new ClassificationException("Claude's reply was cut off at max_tokens", false);
		}
		try {
			return response.content().stream()
					.flatMap(b -> b.text().stream())
					.map(t -> t.text())
					.findFirst()
					.orElseThrow(() -> new ClassificationException("Claude returned no text content", false));
		}
		catch (AnthropicException e) {
			throw new ClassificationException("Claude returned unparseable output: " + e.getMessage(), e, false);
		}
	}

	static String renderEmail(MailMessage m) {
		String body = m.bodyText() == null ? "" : m.bodyText();
		if (body.length() > MAX_BODY_CHARS) {
			body = body.substring(0, MAX_BODY_CHARS);
		}
		return "From: " + m.from() + "\nSubject: " + m.subject() + "\nDate: " + m.receivedAt() + "\n\n" + body;
	}

	private static String loadPrompt() {
		try (InputStream in = ClaudeEmailClassifier.class.getResourceAsStream("/prompts/classify-system.txt")) {
			if (in == null) {
				throw new IllegalStateException("Missing prompts/classify-system.txt");
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
		catch (IOException e) {
			throw new IllegalStateException("Cannot read classify-system.txt", e);
		}
	}
}
