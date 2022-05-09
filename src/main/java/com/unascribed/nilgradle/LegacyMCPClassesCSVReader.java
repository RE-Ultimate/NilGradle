package com.unascribed.nilgradle;

import java.io.Reader;
import java.util.Map;

import org.cadixdev.lorenz.MappingSet;

public class LegacyMCPClassesCSVReader extends CSVMappingsReader {

	public LegacyMCPClassesCSVReader(final Reader reader) {
		super(reader, Processor::new);
	}
	
	public static class Processor extends CSVMappingsReader.MapProcessor {

		public Processor(MappingSet mappings) {
			super(mappings);
		}

		@Override
		protected void accept(Map<String, String> entries) {
			this.mappings.getOrCreateClassMapping(entries.get("notch"))
				.setDeobfuscatedName(entries.get("name"));
		}
		
	}

}
