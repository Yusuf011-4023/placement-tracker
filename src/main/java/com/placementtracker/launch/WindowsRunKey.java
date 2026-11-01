package com.placementtracker.launch;

import java.util.Optional;

import com.sun.jna.platform.win32.Advapi32Util;
import com.sun.jna.platform.win32.WinReg;

/** {@link RunKey} backed by {@code HKEY_CURRENT_USER\Software\Microsoft\Windows\CurrentVersion\Run}. */
public class WindowsRunKey implements RunKey {

	static final String PATH = "Software\\Microsoft\\Windows\\CurrentVersion\\Run";

	@Override
	public Optional<String> read(String name) {
		if (!Advapi32Util.registryValueExists(WinReg.HKEY_CURRENT_USER, PATH, name)) {
			return Optional.empty();
		}
		return Optional.of(Advapi32Util.registryGetStringValue(WinReg.HKEY_CURRENT_USER, PATH, name));
	}

	@Override
	public void write(String name, String command) {
		Advapi32Util.registrySetStringValue(WinReg.HKEY_CURRENT_USER, PATH, name, command);
	}

	@Override
	public void delete(String name) {
		if (Advapi32Util.registryValueExists(WinReg.HKEY_CURRENT_USER, PATH, name)) {
			Advapi32Util.registryDeleteValue(WinReg.HKEY_CURRENT_USER, PATH, name);
		}
	}
}
