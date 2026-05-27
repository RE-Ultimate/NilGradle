/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.cadixdev.atlas.util;

import java.io.IOException;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemAlreadyExistsException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.gradle.api.UncheckedIOException;

/**
 * A helper class for interacting with Java's NIO.
 *
 * @author Jamie Mansfield
 * @since 0.1.0
 */
public final class NIOHelper {

	private static final ConcurrentMap<Path, FileSystem> cache = new ConcurrentHashMap<>();

	/**
	 * Opens the specified zip file, optionally creating it if it doesn't
	 * exist.
	 *
	 * @param path The path to the zip file
	 * @param create Whether to create the file, should it not exist
	 * @return The file system for the zip file
	 * @throws IOException Should an issue occur while opening the zip file
	 */
	public static FileSystem openZip(final Path path, final boolean create) throws IOException {
		final URI uri = URI.create("jar:" + path.toUri());
		final Map<String, String> options = new HashMap<>();
		options.put("create", Boolean.toString(create));
		return cache.compute(path, (unused, cur) -> {
			try {
				return FileSystems.newFileSystem(uri, options);
			} catch (FileSystemAlreadyExistsException e) {
				if (cur == null) {
					throw new IllegalStateException("A stale ZipFileSystem refers to a file we want to use, but don't hold a handle for. Due to JVM limitations, this Gradle daemon is tainted and must be restarted.");
				}
				return cur;
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
		});
	}

	private NIOHelper() {
	}

}
