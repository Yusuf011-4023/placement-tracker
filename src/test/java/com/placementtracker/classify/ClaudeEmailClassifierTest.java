package com.placementtracker.classify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.anthropic.core.JsonValue;
import com.anthropic.core.http.Headers;
import com.anthropic.errors.InternalServerException;
import com.anthropic.errors.NotFoundException;
import com.anthropic.errors.RateLimitException;
import com.anthropic.models.messages.CacheCreation;
import com.anthropic.models.messages.Container;
import com.anthropic.models.messages.Diagnostics;
import com.anthropic.models.messages.RefusalStopDetails;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.OutputTokensDetails;
import com.anthropic.models.messages.ServerToolUsage;
import com.anthropic.models.messages.Model;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.StructuredMessage;
import com.anthropic.models.messages.StructuredMessageCreateParams;
import com.anthropic.models.messages.TextBlock;
import com.anthropic.models.messages.Usage;
import com.placementtracker.application.Stage;
import com.placementtracker.config.TrackerProperties;
import com.placementtracker.mail.MailAccount;
import com.placementtracker.mail.MailMessage;

class ClaudeEmailClassifierTest {

	private static final String JSON = """
			{"relevance":"YES","company":"Northwind Bank","role":"Placement Analyst","stage":"ONLINE_TEST",
			 "actions":[{"description":"Complete the online test","dueAt":"2026-11-07"}],
			 "confidence":0.93,"reason":"Test invitation"}""";

	private final TrackerProperties props = new TrackerProperties(null, null,
			new TrackerProperties.Ai("claude-haiku-5-5", 0.7, 3));

	private final List<StructuredMessageCreateParams<Classification>> captured = new ArrayList<>();

	private static MailMessage mail(String body) {
		return new MailMessage(MailAccount.GMAIL, "m1", "t1", "<i@x>", "Northwind Careers <jobs@northwind.example>",
				"Your online test", Instant.parse("2026-10-29T09:30:00Z"), body, "http://x", Set.of());
	}

	private static StructuredMessage<Classification> reply(String text, StopReason stop) {
		Message raw = Message.builder().id("msg_1").model(Model.of("claude-haiku-5-5")).stopReason(stop)
				.stopSequence((String) null)
				.container((Container) null).diagnostics((Diagnostics) null).stopDetails((RefusalStopDetails) null)
				.usage(Usage.builder().inputTokens(1L).outputTokens(1L).cacheCreationInputTokens(0L)
						.cacheReadInputTokens(0L).cacheCreation((CacheCreation) null)
						.inferenceGeo((String) null).serverToolUse((ServerToolUsage) null)
						.serviceTier((Usage.ServiceTier) null)
						.outputTokensDetails((OutputTokensDetails) null).speed((Usage.Speed) null).build())
				.addContent(TextBlock.builder().text(text).citations(List.of()).build()).build();
		return new StructuredMessage<>(Classification.class, raw);
	}

	private ClaudeEmailClassifier classifierReturning(String text, StopReason stop) {
		return new ClaudeEmailClassifier(p -> {
			captured.add(p);
			return reply(text, stop);
		}, props);
	}

	private static String userText(StructuredMessageCreateParams<Classification> p) {
		return p.messages().get(0).content().asString();
	}

	@Test
	void sendsSenderSubjectDateAndBodyOnly() throws Exception {
		classifierReturning(JSON, StopReason.END_TURN).classify(mail("Please do the test by Friday."));
		String user = userText(captured.get(0));
		assertThat(user).contains("jobs@northwind.example", "Your online test", "2026-10-29T09:30:00Z",
				"Please do the test by Friday.");
		assertThat(user).doesNotContain("http://x", "m1", "t1");
		assertThat(captured.get(0).messages()).hasSize(1);
		assertThat(captured.get(0).rawParams().system()).isPresent();
		assertThat(captured.get(0).maxTokens()).isEqualTo(4096L);
		assertThat(captured.get(0).thinking()).isEmpty();
	}

	@Test
	void usesConfiguredModel() throws Exception {
		classifierReturning(JSON, StopReason.END_TURN).classify(mail("b"));
		assertThat(captured.get(0).model().asString()).isEqualTo("claude-haiku-5-5");
	}

	@Test
	void returnsTypedClassification() throws Exception {
		Classification c = classifierReturning(JSON, StopReason.END_TURN).classify(mail("b"));
		assertThat(c.relevance()).isEqualTo(Relevance.YES);
		assertThat(c.company()).isEqualTo("Northwind Bank");
		assertThat(c.stage()).isEqualTo(Stage.ONLINE_TEST);
		assertThat(c.actions()).containsExactly(new SuggestedAction("Complete the online test", "2026-11-07"));
		assertThat(c.confidence()).isEqualTo(0.93);
	}

	@Test
	void refusalThrowsClassificationException() {
		assertThatThrownBy(() -> classifierReturning("", StopReason.REFUSAL).classify(mail("b")))
				.isInstanceOf(ClassificationException.class)
				.satisfies(e -> assertThat(((ClassificationException) e).isRetryable()).isFalse());
	}

	@Test
	void maxTokensThrowsClassificationException() {
		assertThatThrownBy(() -> classifierReturning("{", StopReason.MAX_TOKENS).classify(mail("b")))
				.isInstanceOf(ClassificationException.class);
	}

	@Test
	void malformedOutputIsNonRetryableClassificationException() {
		assertThatThrownBy(() -> classifierReturning("not json", StopReason.END_TURN).classify(mail("b")))
				.isInstanceOf(ClassificationException.class)
				.satisfies(e -> assertThat(((ClassificationException) e).isRetryable()).isFalse());
	}

	@Test
	void apiErrorThrowsClassificationException() {
		Headers h = Headers.builder().build();
		ClaudeEmailClassifier rateLimited = new ClaudeEmailClassifier(p -> {
			throw RateLimitException.builder().headers(h).body(JsonValue.from("x")).build();
		}, props);
		ClaudeEmailClassifier serverError = new ClaudeEmailClassifier(p -> {
			throw InternalServerException.builder().statusCode(503).headers(h).body(JsonValue.from("x")).build();
		}, props);
		ClaudeEmailClassifier notFound = new ClaudeEmailClassifier(p -> {
			throw NotFoundException.builder().headers(h).body(JsonValue.from("x")).build();
		}, props);
		assertThatThrownBy(() -> rateLimited.classify(mail("b"))).isInstanceOf(ClassificationException.class)
				.satisfies(e -> assertThat(((ClassificationException) e).isRetryable()).isTrue());
		assertThatThrownBy(() -> serverError.classify(mail("b"))).isInstanceOf(ClassificationException.class)
				.satisfies(e -> assertThat(((ClassificationException) e).isRetryable()).isTrue());
		assertThatThrownBy(() -> notFound.classify(mail("b"))).isInstanceOf(ClassificationException.class)
				.satisfies(e -> assertThat(((ClassificationException) e).isRetryable()).isFalse());
	}

	@Test
	void billingErrorIsRetryable() {
		Headers h = Headers.builder().build();
		// 402 (billing) and 408 (timeout) have no dedicated SDK class; the owner fixes billing, a timeout passes.
		ClaudeEmailClassifier billing = new ClaudeEmailClassifier(p -> {
			throw com.anthropic.errors.UnexpectedStatusCodeException.builder().statusCode(402).headers(h)
					.body(JsonValue.from("x")).build();
		}, props);
		ClaudeEmailClassifier timeout = new ClaudeEmailClassifier(p -> {
			throw com.anthropic.errors.UnexpectedStatusCodeException.builder().statusCode(408).headers(h)
					.body(JsonValue.from("x")).build();
		}, props);
		ClaudeEmailClassifier retriesExhausted = new ClaudeEmailClassifier(p -> {
			throw new com.anthropic.errors.AnthropicRetryableException("gave up");
		}, props);
		ClaudeEmailClassifier badRequest = new ClaudeEmailClassifier(p -> {
			throw com.anthropic.errors.BadRequestException.builder().headers(h).body(JsonValue.from("x")).build();
		}, props);
		assertThatThrownBy(() -> billing.classify(mail("b"))).isInstanceOf(ClassificationException.class)
				.satisfies(e -> assertThat(((ClassificationException) e).isRetryable()).isTrue());
		assertThatThrownBy(() -> timeout.classify(mail("b"))).isInstanceOf(ClassificationException.class)
				.satisfies(e -> assertThat(((ClassificationException) e).isRetryable()).isTrue());
		assertThatThrownBy(() -> retriesExhausted.classify(mail("b"))).isInstanceOf(ClassificationException.class)
				.satisfies(e -> assertThat(((ClassificationException) e).isRetryable()).isTrue());
		assertThatThrownBy(() -> badRequest.classify(mail("b"))).isInstanceOf(ClassificationException.class)
				.satisfies(e -> assertThat(((ClassificationException) e).isRetryable()).isFalse());
	}

	@Test
	void authErrorIsRetryable() {
		Headers h = Headers.builder().build();
		ClaudeEmailClassifier unauthorized = new ClaudeEmailClassifier(p -> {
			throw com.anthropic.errors.UnauthorizedException.builder().headers(h).body(JsonValue.from("x")).build();
		}, props);
		ClaudeEmailClassifier forbidden = new ClaudeEmailClassifier(p -> {
			throw com.anthropic.errors.PermissionDeniedException.builder().headers(h).body(JsonValue.from("x")).build();
		}, props);
		assertThatThrownBy(() -> unauthorized.classify(mail("b"))).isInstanceOf(ClassificationException.class)
				.satisfies(e -> assertThat(((ClassificationException) e).isRetryable()).isTrue());
		assertThatThrownBy(() -> forbidden.classify(mail("b"))).isInstanceOf(ClassificationException.class)
				.satisfies(e -> assertThat(((ClassificationException) e).isRetryable()).isTrue());
	}

	@Test
	void bodyTruncatedTo20kChars() throws Exception {
		classifierReturning(JSON, StopReason.END_TURN).classify(mail("a".repeat(20_000) + "TAIL"));
		String user = userText(captured.get(0));
		assertThat(user).contains("a".repeat(20_000)).doesNotContain("TAIL");
	}

	@Test
	void apiKeyFromConfigPropertiesWhenEnvMissing(@TempDir Path dir) throws Exception {
		Path f = dir.resolve("config.properties");
		Files.writeString(f, "anthropic.api-key=sk-from-file\n");
		assertThat(AnthropicConfig.resolveApiKey(null, f)).isEqualTo("sk-from-file");
		assertThat(AnthropicConfig.resolveApiKey("sk-env", f)).isEqualTo("sk-env");
		assertThat(AnthropicConfig.resolveApiKey("", dir.resolve("none.properties"))).isNull();
	}

	@Test
	void missingKeyFailsFastWithoutCall() {
		ClaudeEmailClassifier c = new ClaudeEmailClassifier(p -> {
			throw new AssertionError("API must not be called without a key");
		}, props, false);
		assertThatThrownBy(() -> c.classify(mail("b"))).isInstanceOf(ClassificationException.class)
				.hasMessage("Anthropic API key not configured")
				.satisfies(e -> assertThat(((ClassificationException) e).isRetryable()).isTrue());
	}

	@Test
	void isConfiguredReflectsKeyPresence() {
		assertThat(new ClaudeEmailClassifier(p -> null, props, false).isConfigured()).isFalse();
		assertThat(new ClaudeEmailClassifier(p -> null, props).isConfigured()).isTrue();
	}
}
