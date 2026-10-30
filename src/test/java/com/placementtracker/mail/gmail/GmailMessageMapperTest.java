package com.placementtracker.mail.gmail;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.gmail.model.Message;
import com.google.api.services.gmail.model.MessagePart;
import com.google.api.services.gmail.model.MessagePartBody;
import com.google.api.services.gmail.model.MessagePartHeader;
import com.placementtracker.mail.MailAccount;
import com.placementtracker.mail.MailMessage;

class GmailMessageMapperTest {

	private static Message load(String name) throws IOException {
		try (InputStream in = GmailMessageMapperTest.class.getResourceAsStream("/gmail/" + name)) {
			return GsonFactory.getDefaultInstance().fromInputStream(in, Message.class);
		}
	}

	@Test
	void prefersTextPlainPart() throws IOException {
		MailMessage m = GmailMessageMapper.toMailMessage(load("plain-and-html.json"));
		assertThat(m.bodyText()).isEqualTo("Hello plain body, café");
		assertThat(m.bodyText()).doesNotContain("attachment text");
		assertThat(m.account()).isEqualTo(MailAccount.GMAIL);
		assertThat(m.from()).isEqualTo("Jobs <jobs@acme.com>");
		assertThat(m.subject()).isEqualTo("Your application");
		assertThat(m.threadId()).isEqualTo("t1");
	}

	@Test
	void fallsBackToHtmlConvertedToText() throws IOException {
		MailMessage m = GmailMessageMapper.toMailMessage(load("html-only.json"));
		assertThat(m.bodyText()).isEqualTo("Hello\nHTML only\nline two");
	}

	@Test
	void decodesBase64UrlBodies() throws IOException {
		assertThat(GmailMessageMapper.toMailMessage(load("plain-and-html.json")).bodyText()).contains("café");
		assertThat(GmailMessageMapper.toMailMessage(load("plain-and-html.json")).receivedAt())
				.isEqualTo(Instant.ofEpochMilli(1760000000000L));
	}

	@Test
	void mapsCategoryLabels() throws IOException {
		assertThat(GmailMessageMapper.toMailMessage(load("plain-and-html.json")).labels())
				.isEqualTo(Set.of("PROMOTIONS", "SPAM"));
		assertThat(GmailMessageMapper.toMailMessage(load("html-only.json")).labels())
				.isEqualTo(Set.of("SOCIAL", "UPDATES"));
	}

	@Test
	void webLinkPointsAtMessage() throws IOException {
		assertThat(GmailMessageMapper.toMailMessage(load("plain-and-html.json")).webLink())
				.isEqualTo("https://mail.google.com/mail/u/0/#all/m1");
	}

	@Test
	void readsInternetMessageIdHeader() throws IOException {
		assertThat(GmailMessageMapper.toMailMessage(load("plain-and-html.json")).internetMessageId())
				.isEqualTo("<abc@mail.acme.com>");
		assertThat(GmailMessageMapper.toMailMessage(load("html-only.json")).internetMessageId())
				.isEqualTo("<m2@x.com>");
	}

	@Test
	void charsetFollowedByTabIsHonoured() {
		byte[] latin1 = "Caf\u00e9 interview".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
		MessagePart part = new MessagePart().setMimeType("text/plain")
				.setHeaders(java.util.List.of(new MessagePartHeader().setName("Content-Type")
						.setValue("text/plain; charset=ISO-8859-1\tformat=flowed")))
				.setBody(new MessagePartBody().encodeData(latin1));
		Message m = new Message().setId("x").setThreadId("t").setInternalDate(0L).setPayload(part);

		assertThat(GmailMessageMapper.toMailMessage(m).bodyText()).isEqualTo("Caf\u00e9 interview");
	}
}
