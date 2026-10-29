package com.placementtracker.web;

import java.util.List;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import com.placementtracker.mail.MailSource;
import com.placementtracker.sync.SyncState;
import com.placementtracker.sync.SyncStateRepository;
import com.placementtracker.sync.SyncStatus;
import com.placementtracker.web.dto.Dtos.Account;

/** The mail sources that exist, with their connection flag and last sync state. */
@Component
public class AccountViews {

	private final ObjectProvider<MailSource> sources;
	private final SyncStateRepository syncState;

	public AccountViews(ObjectProvider<MailSource> sources, SyncStateRepository syncState) {
		this.sources = sources;
		this.syncState = syncState;
	}

	public List<Account> list() {
		return sources.orderedStream().map(s -> {
			SyncState st = syncState.get(s.account());
			// A token on disk that Google has rejected is not a connection: the owner must reconnect.
			boolean connected = s.isConnected() && st.status() != SyncStatus.NEEDS_RECONNECT;
			return new Account(s.account().name(), connected, st.status().name(), st.lastSuccessAt(), st.lastError());
		}).toList();
	}

	public MailSource find(String name) {
		return sources.orderedStream().filter(s -> s.account().name().equalsIgnoreCase(name)).findFirst()
				.orElse(null);
	}
}
