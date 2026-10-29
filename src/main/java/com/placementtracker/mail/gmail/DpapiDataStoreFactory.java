package com.placementtracker.mail.gmail;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputFilter;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;

import com.google.api.client.util.store.AbstractDataStoreFactory;
import com.google.api.client.util.store.AbstractMemoryDataStore;
import com.google.api.client.util.store.DataStore;
import com.sun.jna.platform.win32.Crypt32Util;

/**
 * Data store factory keeping each store in {@code <dir>/<id>.bin}, encrypted with Windows DPAPI
 * (current-user scope), so tokens are never written in plaintext.
 */
public class DpapiDataStoreFactory extends AbstractDataStoreFactory {

	private final Path dir;

	public DpapiDataStoreFactory(Path dir) {
		this.dir = dir;
	}

	@Override
	protected <V extends Serializable> DataStore<V> createDataStore(String id) throws IOException {
		Files.createDirectories(dir);
		return new DpapiDataStore<>(this, file(id), id);
	}

	private Path file(String id) {
		return dir.resolve(id.replaceAll("[^A-Za-z0-9._-]", "_") + ".bin");
	}

	/**
	 * Moves an unreadable store file aside (to {@code <id>.bin.unreadable-<millis>}) so the store starts empty.
	 * A store that failed to load is never cached by the base factory, so the next lookup reads afresh.
	 */
	public void discardUnreadable(String id) throws IOException {
		Path f = file(id);
		if (Files.exists(f)) {
			Files.move(f, f.resolveSibling(f.getFileName() + ".unreadable-" + System.currentTimeMillis()),
					StandardCopyOption.REPLACE_EXISTING);
		}
	}

	/** The store file exists but cannot be decrypted or deserialized (other Windows user, or corrupt). */
	public static class UnreadableStoreException extends IOException {

		private static final long serialVersionUID = 1L;

		UnreadableStoreException(String message, Throwable cause) {
			super(message, cause);
		}
	}

	static class DpapiDataStore<V extends Serializable> extends AbstractMemoryDataStore<V> {

		private final Path file;

		DpapiDataStore(DpapiDataStoreFactory factory, Path file, String id) throws IOException {
			super(factory, id);
			this.file = file;
			if (Files.exists(file) && Files.size(file) > 0) {
				keyValueMap = read(Files.readAllBytes(file));
			}
			else {
				keyValueMap = new HashMap<>();
			}
		}

		@Override
		public void save() throws IOException {
			ByteArrayOutputStream bytes = new ByteArrayOutputStream();
			try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
				out.writeObject(keyValueMap);
			}
			byte[] encrypted = Crypt32Util.cryptProtectData(bytes.toByteArray());
			Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
			Files.write(tmp, encrypted);
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
		}

		@SuppressWarnings("unchecked")
		private static HashMap<String, byte[]> read(byte[] encrypted) throws IOException {
			try {
				byte[] plain = Crypt32Util.cryptUnprotectData(encrypted);
				try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(plain))) {
					in.setObjectInputFilter(info -> {
						Class<?> c = info.serialClass();
						boolean ok = c == null || c == HashMap.class || c == String.class || c == byte[].class
								|| c == java.util.Map.Entry[].class;
						return ok ? ObjectInputFilter.Status.ALLOWED : ObjectInputFilter.Status.REJECTED;
					});
					return (HashMap<String, byte[]>) in.readObject();
				}
			}
			catch (ClassNotFoundException | IOException | RuntimeException e) {
				throw new UnreadableStoreException(
						"Cannot decrypt token store (different Windows user, or corrupt file)", e);
			}
		}
	}
}
