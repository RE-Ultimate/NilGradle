package com.unascribed.nilgradle;

import java.io.Reader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.cadixdev.lorenz.MappingSet;
import org.cadixdev.lorenz.io.TextMappingsReader;

public abstract class CSVMappingsReader extends TextMappingsReader {

	public CSVMappingsReader(Reader reader, Function<MappingSet, TextMappingsReader.Processor> processor) {
		super(reader, processor);
	}

	public abstract static class Processor extends TextMappingsReader.Processor {
	
		private static final Pattern ENTRY = Pattern.compile("(?:\"(.*?)(?<!\\\\)\"|(.*?))(?:,|$)");
		
		public Processor(final MappingSet mappings) {
			super(mappings);
		}
	
		@Override
		public void accept(final String line) {
			List<String> entries = new ArrayList<>();
			Matcher m = ENTRY.matcher(line);
			while (m.find()) {
				entries.add(m.group(1) == null ? m.group(2) : m.group(1));
			}

			accept(entries);
		}

		protected abstract void accept(List<String> entries);
	
	}
	
	public abstract static class MapProcessor extends Processor {
		
		private boolean firstLine = true;
		private List<String> header;
		
		public MapProcessor(final MappingSet mappings) {
			super(mappings);
		}
	
		@Override
		public void accept(List<String> entries) {
			if (firstLine) {
				header = entries;
				firstLine = false;
				return;
			}
			
			Map<String, String> map = new LinkedHashMap<>();
			for (int i = 0; i < entries.size(); i++) {
				String k;
				if (i >= header.size()) {
					k = "#"+i;
				} else {
					k = header.get(i);
				}
				map.put(k, entries.get(i));
			}
			accept(map);
		}

		protected abstract void accept(Map<String, String> entries);
	
	}

}