package com.unascribed.nilgradle;

import org.cadixdev.lorenz.MappingSet;
import org.cadixdev.lorenz.io.TextMappingsReader;
import org.cadixdev.lorenz.io.srg.SrgConstants;
import org.codehaus.groovy.runtime.DefaultGroovyMethods;

import java.io.Reader;
import java.util.Arrays;
import java.util.Set;

/**
 * Based on SrgReader
 */
public class RetroguardReader extends TextMappingsReader {

	public RetroguardReader(final Reader reader) {
		super(reader, RetroguardReader.Processor::new);
	}

	public static class Processor extends TextMappingsReader.Processor {

		private static final Set<String> JUNK = DefaultGroovyMethods.toSet(Arrays.asList(
				".option", ".class", ".field", ".method"
			));
		
		private static final String CLASS_MAPPING_KEY = ".class_map";
		private static final String FIELD_MAPPING_KEY = ".field_map";
		private static final String METHOD_MAPPING_KEY = ".method_map";

		private static final int CLASS_MAPPING_ELEMENT_COUNT = 3;
		private static final int FIELD_MAPPING_ELEMENT_COUNT = 3;
		private static final int METHOD_MAPPING_ELEMENT_COUNT = 4;

		public Processor(final MappingSet mappings) {
			super(mappings);
		}

		@Override
		public void accept(final String rawLine) {
			final String line = SrgConstants.removeComments(rawLine).trim();
			if (line.isEmpty())
				return;

			if (line.length() < 4) {
				throw new IllegalArgumentException(
						"Faulty RGS mapping encountered: `" + line + "`!");
			}

			// Split up the line, for further processing
			final String[] split = SPACE.split(line);
			final int len = split.length;

			// Establish the type of mapping
			final String key = split[0];
			if (JUNK.contains(key))
				return;
			
			if (key.equals(CLASS_MAPPING_KEY)
					&& len == CLASS_MAPPING_ELEMENT_COUNT) {
				final String obfuscatedName = split[1];
				final String deobfuscatedName = split[2];

				// Get mapping, and set de-obfuscated name
				this.mappings.getOrCreateClassMapping(obfuscatedName)
						.setDeobfuscatedName(deobfuscatedName);
			} else if (key.equals(FIELD_MAPPING_KEY)
					&& len == FIELD_MAPPING_ELEMENT_COUNT) {
				final String fullObfuscatedName = split[1];
				final String deobfuscatedName = split[2];
				final int lastIndex = fullObfuscatedName.lastIndexOf('/');
				final String owningClass = fullObfuscatedName.substring(0,
						lastIndex);
				final String obfuscatedName = fullObfuscatedName
						.substring(lastIndex + 1);

				// Get mapping, and set de-obfuscated name
				this.mappings.getOrCreateClassMapping(owningClass)
						.getOrCreateFieldMapping(obfuscatedName)
						.setDeobfuscatedName(deobfuscatedName);
			} else if (key.equals(METHOD_MAPPING_KEY)
					&& len == METHOD_MAPPING_ELEMENT_COUNT) {
				final String fullObfuscatedName = split[1];
				final String obfuscatedSignature = split[2];
				final String deobfuscatedName = split[3];
				final int lastIndex = fullObfuscatedName.lastIndexOf('/');
				final String owningClass = fullObfuscatedName.substring(0,
						lastIndex);
				final String obfuscatedName = fullObfuscatedName
						.substring(lastIndex + 1);

				// Get mapping, and set de-obfuscated name
				this.mappings.getOrCreateClassMapping(owningClass)
						.getOrCreateMethodMapping(obfuscatedName,
								obfuscatedSignature)
						.setDeobfuscatedName(deobfuscatedName);
			} else {
				throw new IllegalArgumentException(
						"Found unrecognised key: `" + key + "`!");
			}
		}

	}

}
