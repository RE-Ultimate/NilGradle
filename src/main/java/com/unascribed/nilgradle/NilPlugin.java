package com.unascribed.nilgradle;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PushbackInputStream;
import java.io.PushbackReader;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.cadixdev.atlas.FixedAtlas;
import org.cadixdev.bombe.analysis.InheritanceProvider;
import org.cadixdev.bombe.jar.asm.JarEntryRemappingTransformer;
import org.cadixdev.lorenz.MappingSet;
import org.cadixdev.lorenz.asm.LorenzRemapper;
import org.cadixdev.lorenz.io.proguard.ProGuardReader;
import org.cadixdev.lorenz.io.srg.SrgReader;
import org.cadixdev.lorenz.io.srg.tsrg.TSrgReader;
import org.cadixdev.lorenz.io.srg.tsrg.TSrgWriter;
import org.cadixdev.lorenz.merge.MappingSetMerger;
import org.cadixdev.lorenz.model.ClassMapping;
import org.cadixdev.lorenz.model.TopLevelClassMapping;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.ModuleVersionIdentifier;
import org.gradle.api.artifacts.ResolvedArtifact;
import org.gradle.api.plugins.JavaPlugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.lorenztiny.TinyMappingsReader;
import net.fabricmc.mapping.tree.TinyMappingFactory;
import net.fabricmc.mapping.tree.TinyTree;

public class NilPlugin implements Plugin<Project> {

	private static final Logger log = LoggerFactory.getLogger(NilPlugin.class);
	
	private Project project;
	
	@Override
	public void apply(Project project) {
		this.project = project;
		project.getPlugins().apply(JavaPlugin.class);
		
		project.getRepositories().ivy((repo) -> {
			repo.setName("NilGradle: Mojang launcher data");
			repo.artifactPattern("https://launcher.mojang.com/v1/objects/[revision]/[module].[ext]");
			repo.metadataSources((src) -> {
				src.artifact();
			});
			repo.content((desc) -> {
				desc.includeGroup("com.mojang.launcher");
			});
		});
		
		String[] eras = {
				"Beta",
				"Alpha",
				"Infdev",
				"Indev",
				"Classic",
				"Pre-Classic"
		};
		for (String era : eras) {
			project.getRepositories().ivy((repo) -> {
				repo.setName("NilGradle: archive.org Minecraft-JE-"+era);
				repo.artifactPattern("https://archive.org/download/Minecraft-JE-"+era+"/[module]/[module].[ext]");
				repo.metadataSources((src) -> {
					src.artifact();
				});
				repo.content((desc) -> {
					desc.includeGroup("org.archive.minecraft-je-"+era.toLowerCase(Locale.ROOT));
				});
			});
		}
		
		project.getRepositories().ivy((repo) -> {
			repo.setName("NilGradle: archive.org MCP");
			repo.artifactPattern("https://archive.org/download/minecraftcoderpack/minecraftcoderpack.zip/minecraftcoderpack/[module]/mcp[revision].[ext]");
			repo.metadataSources((src) -> {
				src.artifact();
			});
			repo.content((desc) -> {
				desc.includeGroup("org.archive.minecraftcoderpack");
			});
		});
		
		project.getRepositories().flatDir((repo) -> {
			repo.setName("NilGradle: Dynamic outputs");
			repo.dir(new File(project.getBuildDir(), "nil/cache"));
			repo.content((desc) -> {
				desc.includeGroup("nilgradle.dynamic");
			});
		});
		
		Configuration loaderCfg = project.getConfigurations().create("loader");
		Configuration gameCfg = project.getConfigurations().create("game");
		Configuration remappedGameCfg = project.getConfigurations().create("remappedGame");
		Configuration targetMappingCfg = project.getConfigurations().create("nilInternalTargetMapping", c -> {
			c.setVisible(false);
			c.setCanBeConsumed(false);
		});
		Configuration viaMappingCfg = project.getConfigurations().create("nilInternalViaMapping", c -> {
			c.setVisible(false);
			c.setCanBeConsumed(false);
		});
		Configuration workMappingCfg = project.getConfigurations().create("nilInternalWorkMapping", c -> {
			c.setVisible(false);
			c.setCanBeConsumed(false);
		});
		
		project.getConfigurations().add(loaderCfg);
		project.getConfigurations().add(gameCfg);
		project.getConfigurations().add(remappedGameCfg);
		project.getConfigurations().add(targetMappingCfg);
		project.getConfigurations().add(viaMappingCfg);
		project.getConfigurations().add(workMappingCfg);
		
		project.getConfigurations().getByName("compileClasspath", compile -> {
			compile.extendsFrom(remappedGameCfg);
			compile.extendsFrom(loaderCfg);
		});
		
		NilExtension nilExt = project.getExtensions().create("nil", NilExtension.class);
		nilExt.setProject(project);
		
		MappingsExtension mappingsExt = project.getExtensions().create("mappings", MappingsExtension.class);
		mappingsExt.setProject(project);
		
		project.afterEvaluate(unused -> {
			Set<File> files = gameCfg.resolve();
			if (files.isEmpty()) throw new IllegalStateException("No game dependency present!");
			if (files.size() > 1) throw new IllegalStateException("Multiple game dependencies present!");
			File game = files.iterator().next();
			Map<String, MappingSet> targetMappings = new HashMap<>();
			for (ResolvedArtifact a : targetMappingCfg.getResolvedConfiguration().getResolvedArtifacts()) {
				MappingSet tgt = detectAndLoad(a.getFile());
				ModuleVersionIdentifier v = a.getModuleVersion().getId();
				String name = v.getGroup()+"."+v.getName()+"-"+v.getVersion();
				saveMappings(tgt, "target/raw/"+name+".tsrg");
				targetMappings.put(name, tgt);
			}
			Set<File> workFiles = workMappingCfg.resolve();
			if (!workFiles.isEmpty()) {
				if (workFiles.size() > 1) throw new IllegalStateException("Multiple work mappings present!");
				File workFile = workFiles.iterator().next();
				log.info("Detecting and loading work mappings from "+workFile);
				MappingSet workRaw = detectAndLoad(workFile);
				MappingSet work;
				
				Set<File> viaFiles = viaMappingCfg.resolve();
				if (!viaFiles.isEmpty()) {
					if (viaFiles.size() > 1) throw new IllegalStateException("Multiple work mappings present!");
					File viaFile = viaFiles.iterator().next();
					log.info("Detecting and loading via mappings from "+viaFile);
					MappingSet via = detectAndLoad(viaFile);
					work = MappingSetMerger.create(via, workRaw).merge();
				} else {
					work = workRaw;
				}
				saveMappings(work, "work.tsrg");
				for (Map.Entry<String, MappingSet> en : targetMappings.entrySet()) {
					MappingSet workToTarget = MappingSetMerger.create(work.reverse(), en.getValue()).merge();
					saveMappings(workToTarget, "target/fromWork/"+en.getKey()+".tsrg");
					en.setValue(workToTarget);
				}
				File cache = new File(project.getBuildDir(), "nil/cache/remappedGame.jar");
				if (!cache.exists()) {
					System.out.println("Remapping game...");
					try (FixedAtlas atlas = new FixedAtlas()) {
						atlas.getClasspath().add(game.toPath());
						atlas.install(ctx -> {
							for (TopLevelClassMapping tlcm : work.getTopLevelClassMappings()) {
								completeRecursively(tlcm, ctx.inheritanceProvider());
							}
							return new JarEntryRemappingTransformer(new LorenzRemapper(work, ctx.inheritanceProvider()) {
								@Override
								public String mapMethodName(String owner, String name, String desc) {
									try {
										return super.mapMethodName(owner, name, desc);
									} catch (IllegalStateException e) {
										log.info("Invalid method mapping for owner="+owner+" name="+name+" desc="+desc+" - dazed and confused, but trying to continue", e);
										return name;
									}
								}
							});
						});
						cache.getParentFile().mkdirs();
						atlas.run(game.toPath(), cache.toPath());
					} catch (IOException e) {
						throw new UncheckedIOException(e);
					}
				}
				remappedGameCfg.getDependencies().add(project.getDependencies().create("nilgradle.dynamic:remappedGame"));
			} else {
				// Don't need to remap
				remappedGameCfg.getDependencies().addAll(gameCfg.getDependencies());
			}
		});
	}

	private static void completeRecursively(ClassMapping<?, ?> cm, InheritanceProvider inh) {
		cm.complete(inh);
		for (ClassMapping<?, ?> child : cm.getInnerClassMappings()) {
			child.complete(inh);
		}
	}
	
	private void saveMappings(MappingSet mappings, String filename) {
		File file = new File(project.getBuildDir(), "nil/debug/mappings/"+filename);
		file.getParentFile().mkdirs();
		try (Writer w = new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8)) {
			new TSrgWriter(w).write(mappings);
		} catch (IOException e) {}
	}

	private static final Slice ZIP_HEADER = Slice.parse("[PK]0304");
	private static final Slice TINYv1_HEADER = Slice.parse("[v1\t]");
	private static final Slice TINYv2_HEADER = Slice.parse("[tiny\t2\t0]");
	private static final Slice TSRG2_HEADER = Slice.parse("[tsrg2]");
	private static final Slice SRG_HEADER = Slice.parse("[PK: ]");
	private static final Slice RETROGUARD_HEADER = Slice.parse("[.option ]");
	private static final Slice MCP_CSV_HEADER = Slice.parse("[\"name\",\"notch\"]");

	private MappingSet detectAndLoad(File file) {
		try {
			return detectAndLoad(new FileInputStream(file));
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}
	
	private MappingSet detectAndLoad(InputStream rawIn) {
		try (PushbackInputStream in = new PushbackInputStream(rawIn, 32)) {
			int testByte = in.read();
			if (testByte == -1) {
				throw new IllegalArgumentException("Empty file?");
			}
			in.unread(testByte);
			if (startsWith(in, ZIP_HEADER)) {
				log.info("Examining ZIP file");
				ZipInputStream zip = new ZipInputStream(in);
				ZipEntry ze;
				MappingSet mcpMultipart = null;
				while ((ze = zip.getNextEntry()) != null) {
					if ("config/joined.tsrg".equals(ze.getName())) {
						log.info("Discovered mcp_config joined.tsrg");
						return detectAndLoad(zip);
					} else if ("mappings/mappings.tiny".equals(ze.getName())) {
						log.info("Discovered Fabric mappings.tiny");
						return detectAndLoad(zip);
					} else if ("conf/client.srg".equals(ze.getName())) {
						log.info("Discovered legacy MCP client.srg");
						return detectAndLoad(zip);
					} else if ("conf/minecraft.rgs".equals(ze.getName())) {
						log.info("Discovered legacy MCP minecraft.rgs");
						return detectAndLoad(zip);
					} else if ("conf/classes.csv".equals(ze.getName())) {
						log.info("Discovered legacy MCP classes.csv");
						if (mcpMultipart == null) mcpMultipart = MappingSet.create();
						new MCPClassesCSVReader(new InputStreamReader(zip, StandardCharsets.UTF_8)).read(mcpMultipart);
					} else if ("conf/methods.csv".equals(ze.getName())) {
						log.info("Discovered legacy MCP methods.csv");
						if (mcpMultipart == null) mcpMultipart = MappingSet.create();
						new MCPMethodsCSVReader(new InputStreamReader(zip, StandardCharsets.UTF_8)).read(mcpMultipart);
					} else if ("conf/fields.csv".equals(ze.getName())) {
						log.info("Discovered legacy MCP fields.csv");
						if (mcpMultipart == null) mcpMultipart = MappingSet.create();
						new MCPFieldsCSVReader(new InputStreamReader(zip, StandardCharsets.UTF_8)).read(mcpMultipart);
					}
				}
				zip.close();
				if (mcpMultipart != null) return mcpMultipart;
				throw new IllegalArgumentException("Cannot find mappings inside ZIP");
			} else if (startsWith(in, TINYv1_HEADER)) {
				log.info("Discovered tinyv1");
				TinyTree tree = TinyMappingFactory.loadLegacy(new BufferedReader(new InputStreamReader(in)));
				return load(tree);
			} else if (startsWith(in, TINYv2_HEADER)) {
				log.info("Discovered tinyv2");
				TinyTree tree = TinyMappingFactory.load(new BufferedReader(new InputStreamReader(in)));
				return load(tree);
			}else if (startsWith(in, TSRG2_HEADER)) {
				log.info("Discovered tsrg2");
				return new TSrg2Reader(new BufferedReader(new InputStreamReader(in))).read();
			} else if (startsWith(in, SRG_HEADER)) {
				log.info("Discovered srg");
				return new SrgReader(new InputStreamReader(in)).read();
			} else if (startsWith(in, RETROGUARD_HEADER)) {
				log.info("Discovered retroguard");
				return new RetroguardReader(new BufferedReader(new InputStreamReader(in))).read();
			} else if (startsWith(in, MCP_CSV_HEADER)) {
				log.info("Discovered retroguard");
				return new MCPClassesCSVReader(new BufferedReader(new InputStreamReader(in))).read();
			} else {
				// well, now we're down to formats with no headers...
				
				BufferedReader reader = new BufferedReader(new InputStreamReader(in), 8192);
				
				reader.mark(8192);
				// maybe it's a tsrg?
				try {
					MappingSet ms = new TSrgReader(reader).read();
					log.info("Discovered tsrg");
					return ms;
				} catch (Throwable t) {}
				reader.reset();
				// okay, how about proguard?
				try {
					MappingSet ms = new ProGuardReader(reader).read();
					log.info("Discovered proguard");
					return ms.reverse();
				} catch (Throwable t) {}
				// no idea :(
				throw new IllegalArgumentException("Can't detect mappings format!");
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}
	
	private MappingSet load(TinyTree tree) throws IOException {
		List<String> namespaces = tree.getMetadata().getNamespaces();
		return new TinyMappingsReader(tree, namespaces.get(0), namespaces.get(namespaces.size()-1)).read();
	}

	private boolean startsWith(PushbackInputStream in, Slice slice) throws IOException {
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
	
	private boolean startsWith(PushbackReader r, String str) throws IOException {
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
