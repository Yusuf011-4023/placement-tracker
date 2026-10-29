package com.placementtracker.classify;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.anthropic.client.AnthropicClient;
import com.anthropic.errors.AnthropicException;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicServiceException;
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

	private final ClaudeCall call;
	private final TrackerProperties props;
	private final String systemPrompt;

	@Autowired
	public ClaudeEmailClassifier(AnthropicClient client, TrackerProperties props) {
		this(p -> client.messages().create(p), props);
	}

	ClaudeEmailClassifier(ClaudeCall call, TrackerProperties props) {
		this.call = call;
		this.props = props;
		this.systemPrompt = loadPrompt();
	}

	@Override
	public Classification classify(MailMessage m) throws ClassificationException {
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
		catch (AnthropicServiceException e) {
			int status = e.statusCode();
			boolean retryable = status == 429 || status >= 500;
			throw new ClassificationException("Anthropic API error " + status + ": " + e.getMessage(), e, retryable);
		}
		catch (AnthropicIoException e) {
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
