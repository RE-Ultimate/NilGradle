package com.unascribed.nilgradle;

import java.io.Reader;
import java.util.Map;

import org.cadixdev.lorenz.MappingSet;

public class MCPMethodsCSVReader extends CSVMappingsReader {

	public MCPMethodsCSVReader(final Reader reader) {
		super(reader, Processor::new);
	}
	
	public static class Processor extends CSVMappingsReader.MapProcessor {

		public Processor(MappingSet mappings) {
			super(mappings);
		}

		@Override
		protected void accept(Map<String, String> entries) {
			this.mappings.getOrCreateClassMapping(entries.get("classnotch"))
				.getOrCreateMethodMapping(entries.get("notch"), entries.get("notchsig"))
					.setDeobfuscatedName(entries.get("name"));
		}
		
	}

}
