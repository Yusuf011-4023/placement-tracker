package com.placementtracker.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import com.placementtracker.mail.MailAccount;
import com.placementtracker.mail.MailMessage;
import com.placementtracker.mail.MailSource;
import com.placementtracker.sync.SyncState;
import com.placementtracker.sync.SyncStateRepository;
import com.placementtracker.sync.SyncStatus;
import com.placementtracker.web.dto.Dtos.Account;

class AccountViewsTest {

	/** A source whose token is still on disk, so it believes it is connected. */
	static class TokenOnDisk implements MailSource {
		@Override
		public MailAccount account() {
			return MailAccount.GMAIL;
		}

		@Override
		public boolean isConnected() {
			return true;
		}

		@Override
		public void connect() {
		}

		@Override
		public List<MailMessage> fetchSince(Instant since) {
			return List.of();
		}

		@Override
		public Optional<MailMessage> fetchOne(String messageId) {
			return Optional.empty();
		}
	}

	private AccountViews views(SyncStatus status) {
		SyncStateRepository state = mock(SyncStateRepository.class);
		when(state.get(MailAccount.GMAIL))
				.thenReturn(new SyncState(MailAccount.GMAIL, null, null, status, "invalid_grant"));
		StaticListableBeanFactory beans = new StaticListableBeanFactory(Map.of("gmail", new TokenOnDisk()));
		return new AccountViews(beans.getBeanProvider(MailSource.class), state, mock(SyncRunner.class));
	}

	@Test
	void needsReconnectReportsNotConnected() {
		Account a = views(SyncStatus.NEEDS_RECONNECT).list().get(0);

		assertThat(a.connected()).isFalse();
		assertThat(a.status()).isEqualTo("NEEDS_RECONNECT");
	}

	@Test
	void okReportsConnected() {
		assertThat(views(SyncStatus.OK).list().get(0).connected()).isTrue();
	}
}
