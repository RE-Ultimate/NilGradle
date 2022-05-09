package com.unascribed.nilgradle;

import org.cadixdev.bombe.jar.ClassProvider;
import org.cadixdev.bombe.util.ByteStreams;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

public class DirectoryClassProvider implements ClassProvider {

	private final Path dir;

	public DirectoryClassProvider(Path dir) {
		this.dir = dir.toAbsolutePath();
	}

	@Override
	public byte[] get(String klass) {
		Path p = dir.resolve(klass+".class");
		if (!p.toAbsolutePath().startsWith(dir)) return null;

		try (final InputStream in = Files.newInputStream(p)) {
			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			ByteStreams.copy(in, baos);
			return baos.toByteArray();
		} catch (final IOException ignored) {
			return null;
		}
	}

}
