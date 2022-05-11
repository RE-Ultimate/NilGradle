package com.unascribed.nilgradle;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PushbackInputStream;
import java.io.PushbackReader;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.cadixdev.atlas.FixedAtlas;
import org.cadixdev.atlas.FixedCompositeClassProvider;
import org.cadixdev.bombe.analysis.InheritanceProvider;
import org.cadixdev.bombe.asm.analysis.ClassProviderInheritanceProvider;
import org.cadixdev.bombe.jar.ClassProvider;
import org.cadixdev.bombe.jar.JarFileClassProvider;
import org.cadixdev.bombe.jar.JarManifestEntry;
import org.cadixdev.bombe.jar.JarResourceEntry;
import org.cadixdev.bombe.jar.asm.JarEntryRemappingTransformer;
import org.cadixdev.bombe.type.signature.FieldSignature;
import org.cadixdev.bombe.type.signature.MethodSignature;
import org.cadixdev.lorenz.MappingSet;
import org.cadixdev.lorenz.asm.LorenzRemapper;
import org.cadixdev.lorenz.io.proguard.ProGuardReader;
import org.cadixdev.lorenz.io.srg.SrgReader;
import org.cadixdev.lorenz.io.srg.tsrg.TSrgReader;
import org.cadixdev.lorenz.io.srg.tsrg.TSrgWriter;
import org.cadixdev.lorenz.merge.MappingSetMerger;
import org.cadixdev.lorenz.model.ClassMapping;
import org.cadixdev.lorenz.model.FieldMapping;
import org.cadixdev.lorenz.model.InnerClassMapping;
import org.cadixdev.lorenz.model.Mapping;
import org.cadixdev.lorenz.model.MethodMapping;
import org.cadixdev.lorenz.model.TopLevelClassMapping;
import org.gradle.api.Action;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.ModuleVersionIdentifier;
import org.gradle.api.artifacts.ResolvedArtifact;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.tasks.bundling.Jar;
import org.gradle.api.tasks.compile.JavaCompile;
import org.gradle.language.jvm.tasks.ProcessResources;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.ClassRemapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.grack.nanojson.JsonArray;
import com.grack.nanojson.JsonObject;
import com.grack.nanojson.JsonWriter;

import net.fabricmc.lorenztiny.TinyMappingsReader;
import net.fabricmc.mapping.tree.TinyMappingFactory;
import net.fabricmc.mapping.tree.TinyTree;

public class NilPlugin implements Plugin<Project> {

	public static final Logger log = LoggerFactory.getLogger(NilPlugin.class);
	
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
		Configuration modCfg = project.getConfigurations().create("mod");
		Configuration remappedModCfg = project.getConfigurations().create("remappedMod");
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
		project.getConfigurations().add(modCfg);
		project.getConfigurations().add(remappedModCfg);
		project.getConfigurations().add(targetMappingCfg);
		project.getConfigurations().add(viaMappingCfg);
		project.getConfigurations().add(workMappingCfg);
		
		project.getConfigurations().getByName("compileClasspath", compile -> {
			compile.extendsFrom(remappedGameCfg);
			compile.extendsFrom(remappedModCfg);
			compile.extendsFrom(loaderCfg);
		});
		
		NilExtension nilExt = project.getExtensions().create("nil", NilExtension.class);
		nilExt.setProject(project);
		
		MappingsExtension mappingsExt = project.getExtensions().create("mappings", MappingsExtension.class);
		mappingsExt.setProject(project);

		File mappingsJsonOutFile = new File(project.getBuildDir(), "nil/output/mappings.json");
		ProcessResources process = (ProcessResources)project.getTasks().getByName("processResources");
		// BAD
		process.getInputs().property("cachebuster", UUID.randomUUID().toString());
		process.from(mappingsJsonOutFile, copy -> {
			copy.into("META-INF/nil");
		});
		
		project.afterEvaluate(unused -> {
			if (nilExt.getId() != null && project.getVersion() != null) {
				process.getInputs().property("version", project.getVersion());
				process.filesMatching(nilExt.getId()+".nilmod.css", copy -> {
					copy.expand(Collections.singletonMap("version", project.getVersion()));
				});
			}
			if (nilExt.isStandalone()) {
				Jar jar = (Jar)project.getTasks().getByName("jar");
				for (File f : loaderCfg.resolve()) {
					jar.from(project.zipTree(f));
				}
				jar.getManifest().attributes(Collections.singletonMap("Premain-Class", "nilloader.NilLoader"));
			}
			Set<File> files = gameCfg.resolve();
			if (files.isEmpty()) throw new IllegalStateException("No game dependency present!");
			if (files.size() > 1) throw new IllegalStateException("Multiple game dependencies present!");
			Map<String, MappingSet> targetMappings = new HashMap<>();
			for (ResolvedArtifact a : targetMappingCfg.getResolvedConfiguration().getResolvedArtifacts()) {
				MappingSet tgt = detectAndLoad(a.getFile(), !"srg".equals(a.getClassifier()));
				ModuleVersionIdentifier v = a.getModuleVersion().getId();
				String name = v.getGroup()+"."+v.getName()+"-"+v.getVersion();
				saveDebugMappings(tgt, "target/raw/"+name+".tsrg");
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
				saveDebugMappings(work, "work.tsrg");
				for (Map.Entry<String, MappingSet> en : targetMappings.entrySet()) {
					MappingSet workToTarget = MappingSetMerger.create(work.reverse(), en.getValue()).merge();
					saveDebugMappings(workToTarget, "target/fromWork/"+en.getKey()+".tsrg");
					en.setValue(workToTarget);
				}
				remap(gameCfg, remappedGameCfg, work);
				remap(modCfg, remappedModCfg, work);
				targetMappings.put("default", work.reverse());
			} else {
				// Don't need to remap
				remappedGameCfg.getDependencies().addAll(gameCfg.getDependencies());
			}
			
			if (!targetMappings.isEmpty()) {
				// if this is a lambda, Gradle yells
				// https://docs.gradle.org/7.4.2/userguide/validation_problems.html#implementation_unknown
				process.doFirst(new Action<Task>() {
					@Override
					public void execute(Task task) {
						mappingsJsonOutFile.getParentFile().mkdirs();
						Path classDir = ((JavaCompile)project.getTasks().getByName("compileJava")).getDestinationDirectory().get().getAsFile().toPath();
						if (Files.exists(classDir)) {
							List<ClassProvider> providers = new ArrayList<>();
							for (File f : project.getConfigurations().getByName("compileClasspath").resolve()) {
								try {
									providers.add(new JarFileClassProvider(new JarFile(f)));
								} catch (IOException e) {
									log.warn("Error reading "+f, e);
								}
							}
							providers.add(new DirectoryClassProvider(classDir));
							FixedCompositeClassProvider provider = new FixedCompositeClassProvider(providers);
							InheritanceProvider ip = new ClassProviderInheritanceProvider(Opcodes.ASM9, provider);
							JsonObject out = new JsonObject();
							for (Map.Entry<String, MappingSet> en : targetMappings.entrySet()) {
								for (TopLevelClassMapping tlcm : en.getValue().getTopLevelClassMappings()) {
									completeRecursively(tlcm, ip);
								}
								TrackingLorenzRemapper re = new TrackingLorenzRemapper(en.getValue(), ip);
								try {
									Files.walkFileTree(classDir, new SimpleFileVisitor<Path>() {
										@Override
										public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
											if (file.getFileName().toString().endsWith(".class")) {
												try (InputStream in = Files.newInputStream(file)) {
													ClassReader reader = new ClassReader(in);
													ClassWriter cw = new ClassWriter(reader, 0);
													ClassRemapper remapper = new MiniAwareClassRemapper(cw, re);
													reader.accept(remapper, 0);
													// we don't actually want the class data, but
													// linking a ClassReader to a ClassWriter causes
													// it to properly visit code, which is necessary
													// for us to generate a complete mapping
													cw.toByteArray();
												}
											}
											return FileVisitResult.CONTINUE;
										}

									});
								} catch (IOException e) {
									log.warn("Error while detecting used mappings for target "+en.getKey(), e);
								}
								out.put(en.getKey(), toJson(en.getValue(), re.getUsedMappings()::contains));
							}
							try (OutputStream os = new FileOutputStream(mappingsJsonOutFile)) {
								JsonWriter.indent("\t").on(os).object(out).done();
							} catch (IOException e) {
								log.warn("Error while writing mappings", e);
							}
						} else {
							// no code, no mappings
							try (OutputStream os = new FileOutputStream(mappingsJsonOutFile)) {
								os.write("{}".getBytes(StandardCharsets.UTF_8));
							} catch (IOException e) {
								log.warn("Error while writing mappings", e);
							}
						}
					}
				});
			}
		});
	}

	private void remap(Configuration in, Configuration out, MappingSet mappings) {
		for (ResolvedArtifact artifact : in.getResolvedConfiguration().getResolvedArtifacts()) {
			ModuleVersionIdentifier v = artifact.getModuleVersion().getId();
			String appendix = "";
			if (artifact.getClassifier() != null && !artifact.getClassifier().isEmpty()) {
				appendix = "-"+artifact.getClassifier();
			}
			String name = (v.getGroup()+"."+v.getName()+"-"+v.getVersion()+appendix).replace(':', '_');
			File cache = new File(project.getBuildDir(), "nil/cache/"+name+".jar");
			if (!cache.exists()) {
				System.out.println("Remapping "+name+"...");
				try (FixedAtlas atlas = new FixedAtlas()) {
					atlas.getClasspath().add(artifact.getFile().toPath());
					atlas.install(ctx -> {
						for (TopLevelClassMapping tlcm : mappings.getTopLevelClassMappings()) {
							completeRecursively(tlcm, ctx.inheritanceProvider());
						}
						return new JarEntryRemappingTransformer(new LorenzRemapper(mappings, ctx.inheritanceProvider()) {
							@Override
							public String mapMethodName(String owner, String name, String desc) {
								try {
									return super.mapMethodName(owner, name, desc);
								} catch (IllegalStateException e) {
									log.info("Invalid method mapping for owner="+owner+" name="+name+" desc="+desc+" - dazed and confused, but trying to continue", e);
									return name;
								}
							}

						}) {
							@Override
							public JarManifestEntry transform(final JarManifestEntry entry) {
								try {
									return super.transform(entry);
								} catch (NullPointerException e) {
									// Main-Class is missing
									return entry;
								}
							}
							
							@Override
							public JarResourceEntry transform(final JarResourceEntry entry) {
								if (entry.getName().startsWith("META-INF/")
										&& (entry.getExtension().equalsIgnoreCase("dsa") || entry.getExtension().equalsIgnoreCase("sf"))) {
									return null;
								}
								return entry;
							}
							
						};
					});
					cache.getParentFile().mkdirs();
					atlas.run(artifact.getFile().toPath(), cache.toPath());
				} catch (IOException e) {
					throw new UncheckedIOException(e);
				}
			}
			out.getDependencies().add(project.getDependencies().create("nilgradle.dynamic:"+name));
		}
	}

	private JsonObject toJson(MappingSet mappings, Predicate<Mapping<?, ?>> filter) {
		JsonObject obj = new JsonObject();
		JsonArray classes = new JsonArray();
		for (TopLevelClassMapping tlcm : mappings.getTopLevelClassMappings()) {
			if (filter.test(tlcm)) {
				JsonObject clazz = toJson(tlcm, filter);
				if (clazz != null) classes.add(clazz);
			}
		}
		obj.put("classes", classes);
		return obj;
	}

	private JsonObject toJson(ClassMapping<?, ?> cm, Predicate<Mapping<?, ?>> filter) {
		JsonObject obj = new JsonObject();
		obj.put("from", cm.getFullObfuscatedName());
		obj.put("to", cm.getFullDeobfuscatedName());
		JsonArray inner = new JsonArray();
		for (InnerClassMapping icm : cm.getInnerClassMappings()) {
			if (filter.test(icm)) {
				JsonObject en = toJson(icm, filter);
				if (en != null) inner.add(en);
			}
		}
		if (!inner.isEmpty()) obj.put("inner-classes", inner);
		JsonObject methods = new JsonObject();
		for (MethodMapping mm : cm.getMethodMappings()) {
			if (filter.test(mm)) {
				String from = toString(mm.getSignature());
				String to = toString(mm.getDeobfuscatedSignature());
				if (from.equals(to)) continue;
				methods.put(from, to);
			}
		}
		if (!methods.isEmpty()) obj.put("methods", methods);
		JsonObject fields = new JsonObject();
		for (FieldMapping fm : cm.getFieldMappings()) {
			if (filter.test(fm)) {
				String from = toString(fm.getSignature());
				String to = toString(fm.getDeobfuscatedSignature());
				if (from.equals(to)) continue;
				fields.put(from, to);
			}
		}
		if (!fields.isEmpty()) obj.put("fields", fields);
//		if (cm.getFullObfuscatedName().equals(cm.getFullDeobfuscatedName()) && obj.size() == 2) return null;
		return obj;
	}

	private String toString(FieldSignature signature) {
		return signature.getName()+signature.getType().map(t -> ":"+t).orElse("");
	}

	private String toString(MethodSignature signature) {
		return signature.toJvmsIdentifier();
	}

	private static void completeRecursively(ClassMapping<?, ?> cm, InheritanceProvider inh) {
		cm.complete(inh);
		for (ClassMapping<?, ?> child : cm.getInnerClassMappings()) {
			child.complete(inh);
		}
	}
	
	private void saveDebugMappings(MappingSet mappings, String filename) {
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

	private MappingSet detectAndLoad(File file) {
		return detectAndLoad(file, true);
	}
	
	private MappingSet detectAndLoad(File file, boolean resolveTriplicate) {
		try {
			return detectAndLoad(new FileInputStream(file), resolveTriplicate);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}
	
	private MappingSet detectAndLoad(InputStream rawIn) {
		return detectAndLoad(rawIn, true);
	}
	
	private MappingSet detectAndLoad(InputStream rawIn, boolean resolveTriplicate) {
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
				MappingSet mcpClasses = null;
				String mcpSrg = null;
				String mcpMethods = null;
				String mcpFields = null;
				while ((ze = zip.getNextEntry()) != null) {
					if ("config/joined.tsrg".equals(ze.getName())) {
						log.info("Discovered mcp_config joined.tsrg");
						return detectAndLoad(zip);
					} else if ("mappings/mappings.tiny".equals(ze.getName())) {
						log.info("Discovered Fabric mappings.tiny");
						return detectAndLoad(zip);
					} else if ("conf/client.srg".equals(ze.getName())) {
						log.info("Discovered legacy MCP client.srg");
						if (resolveTriplicate) {
							mcpSrg = getTextWithoutClosing(zip);
						} else {
							return detectAndLoad(zip);
						}
					} else if ("conf/minecraft.rgs".equals(ze.getName())) {
						log.info("Discovered legacy MCP minecraft.rgs");
						return detectAndLoad(zip);
					} else if ("conf/classes.csv".equals(ze.getName())) {
						log.info("Discovered legacy MCP classes.csv");
						mcpClasses = new LegacyMCPClassesCSVReader(new InputStreamReader(zip, StandardCharsets.UTF_8)).read();
					} else if ("conf/methods.csv".equals(ze.getName())) {
						log.info("Discovered legacy MCP methods.csv");
						mcpMethods = getTextWithoutClosing(zip);
					} else if ("conf/fields.csv".equals(ze.getName())) {
						log.info("Discovered legacy MCP fields.csv");
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
	
	private String getTextWithoutClosing(InputStream in) throws IOException {
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
