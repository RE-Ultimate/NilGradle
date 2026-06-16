package com.unascribed.nilgradle;

import org.cadixdev.lorenz.MappingSet;
import org.cadixdev.lorenz.io.MappingsReader;
import org.cadixdev.lorenz.io.TextMappingsReader;
import org.cadixdev.lorenz.io.srg.SrgConstants;

import java.io.Reader;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * An implementation of {@link MappingsReader} for the SRG format.
 *
 * @author Jamie Mansfield
 * @since 0.4.0
 */
public class MiddleMCPTriplicateReader extends TextMappingsReader {

	public MiddleMCPTriplicateReader(Reader srgReader, String fields, String methods, Side side) {
		this(srgReader, fields, methods, side.ordinal());
	}

	public MiddleMCPTriplicateReader(Reader srgReader, String fields, String methods, int side) {
		super(srgReader, mappings -> new MiddleMCPTriplicateReader.Processor(mappings, parse(fields, methods, side)));
	}

	private static Map<String, String> parse(String fields, String methods, int side) {
		Map<String, String> out = new HashMap<>();
		for (String file : Arrays.asList(fields, methods)) {
			boolean firstLine = true;
			for (String line : file.split("\n")) {
				if (firstLine) {
					firstLine = false;
					continue;
				}
				String[] entries = line.split(",", 4);
				try {
					int currSide = Integer.parseInt(entries[2]);
					if (currSide != side) continue;
				} catch (Throwable ignored) {
					// Skip on parsing errors
					continue;
				}
				out.put(entries[0], entries[1]);
			}
		}
		return out;
	}

	public static class Processor extends TextMappingsReader.Processor {

		private static final String PACKAGE_MAPPING_KEY = "PK:";
		private static final String CLASS_MAPPING_KEY = "CL:";
		private static final String FIELD_MAPPING_KEY = "FD:";
		private static final String METHOD_MAPPING_KEY = "MD:";

		private static final int PACKAGE_MAPPING_ELEMENT_COUNT = 3;
		private static final int CLASS_MAPPING_ELEMENT_COUNT = 3;
		private static final int FIELD_MAPPING_ELEMENT_COUNT = 3;
		private static final int METHOD_MAPPING_ELEMENT_COUNT = 5;

		private final Map<String, String> srgMappings;
		
		public Processor(MappingSet mappings, Map<String, String> srgMappings) {
			super(mappings);
			this.srgMappings = srgMappings;
		}

		@Override
		public void accept(String rawLine) {
			String line = SrgConstants.removeComments(rawLine).trim();
			if (line.isEmpty()) return;

			if (line.length() < 4) {
				throw new IllegalArgumentException("Faulty SRG mapping encountered: `" + line + "`!");
			}

			// Split up the line, for further processing
			String[] split = SPACE.split(line);
			int len = split.length;

			// Establish the type of mapping
			String key = split[0];
			if (key.equals(CLASS_MAPPING_KEY) && len == CLASS_MAPPING_ELEMENT_COUNT) {
				String obfuscatedName = split[1];
				String deobfuscatedName = split[2];

				// Get mapping, and set de-obfuscated name
				this.mappings.getOrCreateClassMapping(obfuscatedName)
						.setDeobfuscatedName(deobfuscatedName);
			} else if (key.equals(FIELD_MAPPING_KEY) && len == FIELD_MAPPING_ELEMENT_COUNT) {
				String fullObfuscatedName = split[1];
				String fullSrgName = split[2];
				int lastIndex = fullObfuscatedName.lastIndexOf('/');
				String owningClass = fullObfuscatedName.substring(0, lastIndex);
				String obfuscatedName = fullObfuscatedName.substring(lastIndex + 1);
				String srgName = fullSrgName.substring(fullSrgName.lastIndexOf('/') + 1);
				String deobfuscatedName = srgMappings.getOrDefault(srgName, srgName);

				// Get mapping, and set de-obfuscated name
				this.mappings.getOrCreateClassMapping(owningClass)
						.getOrCreateFieldMapping(obfuscatedName)
						.setDeobfuscatedName(deobfuscatedName);
			} else if (key.equals(METHOD_MAPPING_KEY) && len == METHOD_MAPPING_ELEMENT_COUNT) {
				String fullObfuscatedName = split[1];
				String obfuscatedSignature = split[2];
				String fullSrgName = split[3];
				int lastIndex = fullObfuscatedName.lastIndexOf('/');
				String owningClass = fullObfuscatedName.substring(0, lastIndex);
				String obfuscatedName = fullObfuscatedName.substring(lastIndex + 1);
				String srgName = fullSrgName.substring(fullSrgName.lastIndexOf('/') + 1);
				String deobfuscatedName = srgMappings.getOrDefault(srgName, srgName);

				// Get mapping, and set de-obfuscated name
				this.mappings.getOrCreateClassMapping(owningClass)
						.getOrCreateMethodMapping(obfuscatedName, obfuscatedSignature)
						.setDeobfuscatedName(deobfuscatedName);
			} else if (key.equals(PACKAGE_MAPPING_KEY) && len == PACKAGE_MAPPING_ELEMENT_COUNT) {
				// Lorenz doesn't currently support package mappings, though they are an SRG feature.
				// For now, Lorenz will just silently ignore those mappings.
			} else {
				throw new IllegalArgumentException("Found unrecognised key: `" + key + "`!");
			}
		}

	}

}
