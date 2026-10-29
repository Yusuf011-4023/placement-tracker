package com.placementtracker.mail;

import java.util.Locale;

/** Extracts the bare address from a {@code From} header value such as {@code Name <addr@host>}. */
final class SenderAddress {

	private SenderAddress() {
	}

	/** Lower-cased bare address, or an empty string when there is none. */
	static String of(String from) {
		if (from == null) {
			return "";
		}
		String s = from.trim();
		int lt = s.lastIndexOf('<');
		int gt = s.lastIndexOf('>');
		if (lt >= 0 && gt > lt) {
			s = s.substring(lt + 1, gt);
		}
		return s.trim().toLowerCase(Locale.ROOT);
	}

	/** Lower-cased domain part of the sender, or an empty string when there is none. */
	static String domainOf(String from) {
		String address = of(from);
		int at = address.lastIndexOf('@');
		return at < 0 ? "" : address.substring(at + 1);
	}
}
