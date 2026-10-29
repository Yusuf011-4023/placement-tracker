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
		return new DpapiDataStore<>(this, dir.resolve(id.replaceAll("[^A-Za-z0-9._-]", "_") + ".bin"), id);
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
			catch (ClassNotFoundException | RuntimeException e) {
				throw new IOException("Cannot decrypt token store (different Windows user, or corrupt file)", e);
			}
		}
	}
}
