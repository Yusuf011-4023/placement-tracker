package com.placementtracker.launch;

import java.util.Optional;

/** The per-user Windows "Run" registry key, behind an interface so tests never touch the real registry. */
public interface RunKey {

	Optional<String> read(String name);

	void write(String name, String command);

	void delete(String name);
}
