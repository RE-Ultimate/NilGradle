package com.unascribed.nilgradle;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PushbackInputStream;
import java.io.PushbackReader;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.cadixdev.lorenz.MappingSet;
import org.cadixdev.lorenz.io.proguard.ProGuardReader;
import org.cadixdev.lorenz.io.srg.SrgReader;
import org.cadixdev.lorenz.io.srg.tsrg.TSrgReader;

import net.fabricmc.lorenztiny.TinyMappingsReader;
import net.fabricmc.mapping.tree.TinyMappingFactory;
import net.fabricmc.mapping.tree.TinyTree;

public class MappingDetector {

	private static final Slice ZIP_HEADER = Slice.parse("[PK]0304");
	private static final Slice TINYv1_HEADER = Slice.parse("[v1\t]");
	private static final Slice TINYv2_HEADER = Slice.parse("[tiny\t2\t0]");
	private static final Slice TSRG2_HEADER = Slice.parse("[tsrg2]");
	private static final Slice SRG_HEADER = Slice.parse("[PK: ]");
	private static final Slice RETROGUARD_HEADER = Slice.parse("[.option ]");

	public static MappingSet detectAndLoad(File file) {
		return detectAndLoad(file, true);
	}

	public static MappingSet detectAndLoad(File file, boolean resolveTriplicate) {
		try {
			return detectAndLoad(new FileInputStream(file), resolveTriplicate);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	public static MappingSet detectAndLoad(InputStream rawIn) {
		return detectAndLoad(rawIn, true);
	}

	public static MappingSet detectAndLoad(InputStream rawIn, boolean resolveTriplicate) {
		try (PushbackInputStream in = new PushbackInputStream(rawIn, 32)) {
			int testByte = in.read();
			if (testByte == -1) {
				throw new IllegalArgumentException("Empty file?");
			}
			in.unread(testByte);
			if (startsWith(in, ZIP_HEADER)) {
				NilPlugin.log.info("Examining ZIP file");
				ZipInputStream zip = new ZipInputStream(in);
				ZipEntry ze;
				MappingSet mcpClasses = null;
				String mcpSrg = null;
				String mcpMethods = null;
				String mcpFields = null;
				while ((ze = zip.getNextEntry()) != null) {
					if ("config/joined.tsrg".equals(ze.getName())) {
						NilPlugin.log.info("Discovered mcp_config joined.tsrg");
						return detectAndLoad(zip);
					} else if ("mappings/mappings.tiny".equals(ze.getName())) {
						NilPlugin.log.info("Discovered Fabric mappings.tiny");
						return detectAndLoad(zip);
					} else if ("conf/client.srg".equals(ze.getName())) {
						NilPlugin.log.info("Discovered legacy MCP client.srg");
						if (resolveTriplicate) {
							mcpSrg = getTextWithoutClosing(zip);
						} else {
							return detectAndLoad(zip);
						}
					} else if ("conf/joined.srg".equals(ze.getName())) {
						NilPlugin.log.info("Discovered legacy MCP joined.srg");
						if (resolveTriplicate) {
							mcpSrg = getTextWithoutClosing(zip);
						} else {
							return detectAndLoad(zip);
						}
					} else if ("conf/minecraft.rgs".equals(ze.getName())) {
						NilPlugin.log.info("Discovered legacy MCP minecraft.rgs");
						return detectAndLoad(zip);
					} else if ("conf/classes.csv".equals(ze.getName())) {
						NilPlugin.log.info("Discovered legacy MCP classes.csv");
						mcpClasses = new LegacyMCPClassesCSVReader(new InputStreamReader(zip, StandardCharsets.UTF_8)).read();
					} else if ("conf/methods.csv".equals(ze.getName())) {
						NilPlugin.log.info("Discovered legacy MCP methods.csv");
						mcpMethods = getTextWithoutClosing(zip);
					} else if ("conf/fields.csv".equals(ze.getName())) {
						NilPlugin.log.info("Discovered legacy MCP fields.csv");
						mcpFields = getTextWithoutClosing(zip);
					}
				}
				zip.close();
				if (mcpSrg != null) {
					return new MiddleMCPTriplicateReader(new StringReader(mcpSrg), mcpFields, mcpMethods).read();
				} else if (mcpClasses != null) {
					new LegacyMCPMethodsCSVReader(new StringReader(mcpMethods)).read(mcpClasses);
					new LegacyMCPFieldsCSVReader(new StringReader(mcpFields)).read(mcpClasses);
					return mcpClasses;
				}
				throw new IllegalArgumentException("Cannot find mappings inside ZIP");
			} else if (startsWith(in, TINYv1_HEADER)) {
				NilPlugin.log.info("Discovered tinyv1");
				TinyTree tree = TinyMappingFactory.loadLegacy(new BufferedReader(new InputStreamReader(in)));
				return load(tree);
			} else if (startsWith(in, TINYv2_HEADER)) {
				NilPlugin.log.info("Discovered tinyv2");
				TinyTree tree = TinyMappingFactory.load(new BufferedReader(new InputStreamReader(in)));
				return load(tree);
			} else if (startsWith(in, TSRG2_HEADER)) {
				NilPlugin.log.info("Discovered tsrg2");
				return new TSrg2Reader(new BufferedReader(new InputStreamReader(in))).read();
			} else if (startsWith(in, SRG_HEADER)) {
				NilPlugin.log.info("Discovered srg");
				return new SrgReader(new InputStreamReader(in)).read();
			} else if (startsWith(in, RETROGUARD_HEADER)) {
				NilPlugin.log.info("Discovered retroguard");
				return new RetroguardReader(new BufferedReader(new InputStreamReader(in))).read();
			} else {
				// well, now we're down to formats with no headers...
				
				BufferedReader reader = new BufferedReader(new InputStreamReader(in), 8192);
				
				reader.mark(8192);
				// maybe it's a tsrg?
				try {
					MappingSet ms = new TSrgReader(reader).read();
					NilPlugin.log.info("Discovered tsrg");
					return ms;
				} catch (Throwable t) {}
				reader.reset();
				// okay, how about proguard?
				try {
					MappingSet ms = new ProGuardReader(reader).read();
					NilPlugin.log.info("Discovered proguard");
					return ms.reverse();
				} catch (Throwable t) {}
				// no idea :(
				throw new IllegalArgumentException("Can't detect mappings format!");
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static String getTextWithoutClosing(InputStream in) throws IOException {
		BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
		StringBuilder out = new StringBuilder();
		char[] buf = new char[8192];
		while (true) {
			int read = reader.read(buf);
			if (read < 0) break;
			out.append(buf, 0, read);
		}
		return out.toString();
	}

	private static MappingSet load(TinyTree tree) throws IOException {
		List<String> namespaces = tree.getMetadata().getNamespaces();
		return new TinyMappingsReader(tree, namespaces.get(0), namespaces.get(namespaces.size()-1)).read();
	}

	private static boolean startsWith(PushbackInputStream in, Slice slice) throws IOException {
		byte[] bys = new byte[slice.size()];
		int read = 0;
		while (read < bys.length) {
			int count = in.read(bys, read, bys.length - read);
			if (count < 0) return false;
			read += count;
		}
		boolean res = slice.equals(bys);
		in.unread(bys);
		return res;
	}

	private static boolean startsWith(PushbackReader r, String str) throws IOException {
		char[] chs = new char[str.length()];
		int read = 0;
		while (read < chs.length) {
			int count = r.read(chs, read, chs.length - read);
			if (count < 0) return false;
			read += count;
		}
		boolean res = new String(chs).equals(str);
		r.unread(chs);
		return res;
	}

}
