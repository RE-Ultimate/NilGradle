package com.unascribed.nilgradle;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
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
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import org.cadixdev.atlas.Atlas;
import org.cadixdev.atlas.util.CompositeClassProvider;
import org.cadixdev.bombe.analysis.InheritanceProvider;
import org.cadixdev.bombe.asm.analysis.ClassProviderInheritanceProvider;
import org.cadixdev.bombe.asm.jar.ClassProvider;
import org.cadixdev.bombe.asm.jar.JarEntryRemappingTransformer;
import org.cadixdev.bombe.asm.jar.JarFileClassProvider;
import org.cadixdev.bombe.jar.JarClassEntry;
import org.cadixdev.bombe.jar.JarEntryTransformer;
import org.cadixdev.bombe.jar.JarManifestEntry;
import org.cadixdev.bombe.jar.JarResourceEntry;
import org.cadixdev.bombe.type.FieldType;
import org.cadixdev.bombe.type.signature.FieldSignature;
import org.cadixdev.bombe.type.signature.MethodSignature;
import org.cadixdev.bombe.util.ByteStreams;
import org.cadixdev.lorenz.MappingSet;
import org.cadixdev.lorenz.asm.LorenzRemapper;
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
import org.jetbrains.java.decompiler.main.decompiler.ConsoleDecompiler;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.ClassRemapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.grack.nanojson.JsonArray;
import com.grack.nanojson.JsonObject;
import com.grack.nanojson.JsonWriter;
import com.unascribed.flexver.FlexVerComparator;

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
		Configuration jarmodCfg = project.getConfigurations().create("jarmod");
		Configuration remappedGameCfg = project.getConfigurations().create("remappedGame");
		Configuration modCfg = project.getConfigurations().create("mod");
		Configuration modViaCfg = project.getConfigurations().create("modVia");
		Configuration remappedModCfg = project.getConfigurations().create("remappedMod");
		Action<? super Configuration> setupMappingsConfig = c -> {
			c.setVisible(false);
			c.setCanBeConsumed(false);
		};
		Configuration targetMappingCfg = project.getConfigurations().create("nilInternalTargetMapping", setupMappingsConfig);
		Configuration altTargetMappingCfg = project.getConfigurations().create("nilInternalAltTargetMapping", setupMappingsConfig);
		Configuration viaMappingCfg = project.getConfigurations().create("nilInternalViaMapping", setupMappingsConfig);
		Configuration workMappingCfg = project.getConfigurations().create("nilInternalWorkMapping", setupMappingsConfig);
		
		project.getConfigurations().add(loaderCfg);
		project.getConfigurations().add(gameCfg);
		project.getConfigurations().add(jarmodCfg);
		project.getConfigurations().add(remappedGameCfg);
		project.getConfigurations().add(modCfg);
		project.getConfigurations().add(modViaCfg);
		project.getConfigurations().add(remappedModCfg);
		project.getConfigurations().add(targetMappingCfg);
		project.getConfigurations().add(altTargetMappingCfg);
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
				String version = null;
				Jar jar = (Jar)project.getTasks().getByName("jar");
				for (ResolvedArtifact ra : loaderCfg.getResolvedConfiguration().getResolvedArtifacts()) {
					version = ra.getModuleVersion().getId().getVersion();
					jar.from(project.zipTree(ra.getFile()));
				}
				if (version == null || FlexVerComparator.compare(version, "1.3.0") >= 0) {
					jar.getManifest().attributes(Collections.singletonMap("Premain-Class", "nilloader.NilAgent"));
					jar.getManifest().attributes(Collections.singletonMap("Agent-Class", "nilloader.NilAgent"));
					jar.getManifest().attributes(Collections.singletonMap("Can-Retransform-Classes", "true"));
				} else {
					jar.getManifest().attributes(Collections.singletonMap("Premain-Class", "nilloader.NilLoader"));
				}
			}
			Set<File> gameFiles = gameCfg.resolve();
			if (gameFiles.isEmpty()) throw new IllegalStateException("No game dependency present! Retrieving and remapping a game jar is the entire purpose of NilGradle - if you're making a universal nilmod, you can just use the Gradle Java plugin.");
			if (gameFiles.size() > 1) throw new IllegalStateException("Multiple game dependencies present! If you want to merge jars together, add the additional jars under the 'jarmod' configuration.");
			Map<String, MappingSet> targetMappings = new HashMap<>();
			for (ResolvedArtifact a : targetMappingCfg.getResolvedConfiguration().getResolvedArtifacts()) {
				loadTargetMappings(targetMappings, a, ms -> ms);
			}
			for (ResolvedArtifact a : altTargetMappingCfg.getResolvedConfiguration().getResolvedArtifacts()) {
				loadTargetMappings(targetMappings, a, ms -> ms.reverse());
			}
			Set<File> workFiles = workMappingCfg.resolve();
			if (!workFiles.isEmpty()) {
				if (workFiles.size() > 1) throw new IllegalStateException("Multiple work mappings present!");
				File workFile = workFiles.iterator().next();
				log.info("Detecting and loading work mappings from "+workFile);
				MappingSet workRaw = MappingDetector.detectAndLoad(workFile);
				MappingSet work;
				
				Set<File> viaFiles = viaMappingCfg.resolve();
				if (!viaFiles.isEmpty()) {
					if (viaFiles.size() > 1) throw new IllegalStateException("Multiple work mappings present!");
					File viaFile = viaFiles.iterator().next();
					log.info("Detecting and loading via mappings from "+viaFile);
					MappingSet via = MappingDetector.detectAndLoad(viaFile);
					work = MappingSetMerger.create(via, workRaw).merge();
				} else {
					work = workRaw;
				}
				saveDebugMappings(work, "work.tsrg");
				for (Map.Entry<String, MappingSet> en : targetMappings.entrySet()) {
					MappingSet workToTarget = MappingSetMerger.create(work.reverse(), en.getValue()).merge();
					saveDebugMappings(workToTarget, "target/fromWork/"+en.getKey()+".tsrg");
					saveDebugMappings(workToTarget.reverse(), "target/toWork/"+en.getKey()+".tsrg");
					en.setValue(workToTarget);
				}
				remap(gameCfg, jarmodCfg, remappedGameCfg, work);
				remap(modCfg, null, remappedModCfg, work);
				remap(modViaCfg, null, remappedModCfg, workRaw);
				targetMappings.put("default", work.reverse());
			} else {
				// Don't need to remap
				remappedGameCfg.getDependencies().addAll(gameCfg.getDependencies());
			}
			
			if (!targetMappings.isEmpty()) {
				// if this is a lambda, Gradle yells
				// https://docs.gradle.org/7.4.2/userguide/validation_problems.html#implementation_unknown
				process.doFirst(new Action<Task>() { @Override public void execute(Task task) {
					mappingsJsonOutFile.getParentFile().mkdirs();
					Path classDir = ((JavaCompile)project.getTasks().getByName("compileJava")).getDestinationDirectory().get().getAsFile().toPath();
					if (Files.exists(classDir)) {
						List<ClassProvider> providers = new ArrayList<>();
						for (File f : project.getConfigurations().getByName("compileClasspath").resolve()) {
							try {
								if (f.getName().endsWith(".jar")) {
									File unwidened = new File(f.getParentFile(), f.getName().replaceFirst(".jar$", "-unwidened.jar"));
									if (unwidened.exists()) {
										providers.add(new JarFileClassProvider(new JarFile(unwidened)));
									} else {
										providers.add(new JarFileClassProvider(new JarFile(f)));
									}
								} else if (f.isDirectory()) {
									providers.add(new DirectoryClassProvider(f.toPath()));
								} else {
									providers.add(new JarFileClassProvider(new JarFile(f)));
								}
							} catch (IOException e) {
								log.warn("Error reading "+f, e);
							}
						}
						providers.add(new DirectoryClassProvider(classDir));
						CompositeClassProvider provider = new CompositeClassProvider(providers);
						InheritanceProvider ip = new ClassProviderInheritanceProvider(Opcodes.ASM9, provider);
						JsonObject mappingsOut = new JsonObject();
						Set<String> classesToWiden = new HashSet<>();
						Set<Member> membersToWiden = new HashSet<>();
						for (Map.Entry<String, MappingSet> en : targetMappings.entrySet()) {
							System.err.println("Processing target mappings \""+en.getKey()+"\"");
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
												ClassRemapper remapper = new NilGradleClassRemapper(en.getValue(), cw, re, provider, classesToWiden, membersToWiden);
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
							mappingsOut.put(en.getKey(), toJson(en.getValue(), nilExt.shouldKeepAllMappings() ? e -> true : re.getUsedMappings()::contains, classesToWiden, membersToWiden));
						}
						try (OutputStream os = new FileOutputStream(mappingsJsonOutFile)) {
							JsonWriter.indent("\t").on(os).object(mappingsOut).done();
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
				} });
			}
		});
	}

	private void loadTargetMappings(Map<String, MappingSet> targetMappings, ResolvedArtifact a, Function<MappingSet, MappingSet> configurator) {
		MappingSet tgt = MappingDetector.detectAndLoad(a.getFile(), !"srg".equals(a.getClassifier()));
		tgt = configurator.apply(tgt);
		ModuleVersionIdentifier v = a.getModuleVersion().getId();
		String name = v.getName();
		if (!v.getGroup().isEmpty() && !"mappings".equals(v.getGroup())) {
			name = v.getGroup()+"."+name;
		}
		if (!v.getVersion().isEmpty()) {
			name = name+"-"+v.getVersion();
		}
		saveDebugMappings(tgt, "target/raw/"+name+".tsrg");
		targetMappings.put(name, tgt);
	}

	private void remap(Configuration in, Configuration merge, Configuration out, MappingSet mappings) {
		for (ResolvedArtifact artifact : in.getResolvedConfiguration().getResolvedArtifacts()) {
			ModuleVersionIdentifier v = artifact.getModuleVersion().getId();
			String appendix = "";
			if (artifact.getClassifier() != null && !artifact.getClassifier().isEmpty()) {
				appendix = "-"+artifact.getClassifier();
			}
			String name = (v.getGroup()+"."+v.getName()+"-"+v.getVersion()+appendix).replace(':', '_');
			File merged = merge == null ? artifact.getFile() : new File(project.getBuildDir(), "nil/cache/"+name+"-merged.jar");
			if (!merged.exists() && merge != null) {
				merged.getParentFile().mkdirs();
				Set<String> alreadyCopied = new HashSet<>();
				List<ZipInputStream> inputs = new ArrayList<>();
				try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(merged))) {
					inputs.add(new ZipInputStream(new FileInputStream(artifact.getFile())));
					for (File f : merge.resolve()) {
						inputs.add(new ZipInputStream(new FileInputStream(f)));
					}
					Collections.reverse(inputs);
					for (ZipInputStream zis : inputs) {
						while (true) {
							ZipEntry en = zis.getNextEntry();
							if (en == null) break;
							if (alreadyCopied.contains(en.getName())) continue;
							alreadyCopied.add(en.getName());
							zos.putNextEntry(en);
							ByteStreams.copy(zis, zos);
							zos.closeEntry();
						}
					}
				} catch (IOException e) {
					throw new RuntimeException("Error while merging jars", e);
				} finally {
					for (ZipInputStream zf : inputs) {
						try {
							zf.close();
						} catch (IOException e) {}
					}
				}
			}
			File cache = new File(project.getBuildDir(), "nil/cache/"+name+"-unwidened.jar");
			File widenedCache = new File(project.getBuildDir(), "nil/cache/"+name+".jar");
			if (!cache.exists() || !widenedCache.exists()) {
				System.out.println("Remapping "+name+"...");
				try (Atlas atlas = new Atlas()) {
					atlas.getClasspath().add(merged.toPath());
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
							
							
						}, (cv, re) -> new ClassRemapper(cv, re) {
							@Override
							public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
								MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
								return new MethodVisitor(Opcodes.ASM9, mv) {
									@Override
									public void visitLocalVariable(String varName, String varDescriptor, String varSignature, Label start, Label end, int index) {
										varName = mappings.computeClassMapping(className)
											.map(cm -> cm.getMethodMapping(name, descriptor)).orElse(Optional.empty())
											.map(mm -> mm.getParameterMapping(index)).orElse(Optional.empty())
											.map(pm -> pm.getDeobfuscatedName()).orElse(varName);
										super.visitLocalVariable(varName, varDescriptor, varSignature, start, end, index);
									}
								};
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
					atlas.run(merged.toPath(), cache.toPath());
					atlas.install(ctx -> {
						return new JarEntryTransformer() {
							
							@Override
							public JarClassEntry transform(JarClassEntry entry) {
								ClassReader cr = new ClassReader(entry.getContents());
								ClassWriter cw = new ClassWriter(0);
								cr.accept(new ClassVisitor(Opcodes.ASM9, cw) {
									private String className;
									
									private boolean dontWiden() {
										return project.getExtensions().getByType(NilExtension.class).getDontWiden().contains(className);
									}
									
									private int makePublic(int access) {
										if (dontWiden()) return access;
										if ((access & Opcodes.ACC_PUBLIC) == 0) {
											access = (access & ~(Opcodes.ACC_PRIVATE|Opcodes.ACC_PROTECTED)) | Opcodes.ACC_PUBLIC;
										}
										return access & ~Opcodes.ACC_SYNTHETIC;
									}
									
									private void annotate(int access, BiFunction<String, Boolean, AnnotationVisitor> visit) {
										if (dontWiden()) return;
										if ((access & Opcodes.ACC_PUBLIC) == 0) {
											String ann = null;
											if ((access & Opcodes.ACC_PRIVATE) != 0) {
												ann = "Private";
											} else if ((access & Opcodes.ACC_PROTECTED) != 0) {
												ann = "Protected";
											} else {
												ann = "PackagePrivate";
											}
											visit.apply("Lnilloader/annotate/Originally"+ann+";", false).visitEnd();
										}
										if ((access & Opcodes.ACC_SYNTHETIC) != 0) {
											visit.apply("Lnilloader/annotate/OriginallySynthetic;", false).visitEnd();
										}
									}
									
									@Override
									public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
										className = name;
										super.visit(version, makePublic(access), name, signature, superName, interfaces);
										annotate(access, super::visitAnnotation);
									}
									
									@Override
									public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
										FieldVisitor fv = super.visitField(makePublic(access), name, descriptor, signature, value);
										annotate(access, fv::visitAnnotation);
										return fv;
									}
									
									@Override
									public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
										if ("<clinit>".equals(name)) {
											return super.visitMethod(access, name, descriptor, signature, exceptions);
										}
										MethodVisitor mv = super.visitMethod(makePublic(access), name, descriptor, signature, exceptions);
										annotate(access, mv::visitAnnotation);
										return mv;
									}
									
									@Override
									public void visitInnerClass(String name, String outerName, String innerName, int access) {
										super.visitInnerClass(name, outerName, innerName, makePublic(access));
									}
									
								}, 0);
								return new JarClassEntry(entry.getName(), entry.getTime(), cw.toByteArray());
							}
							
						};
					});
					atlas.run(merged.toPath(), widenedCache.toPath());
					if (project.hasProperty("nil.decompile")) {
						System.out.println("Decompiling...");
						File sources = new File(widenedCache.getParentFile(), widenedCache.getName().replaceFirst(".jar$", "-sources.jar"));
						ConsoleDecompiler.main(new String[] {"-log=ERROR", "-udv=1", "-thr=4", "-ind=\t", widenedCache.getAbsolutePath(), sources.getAbsolutePath()});
					}
				} catch (IOException e) {
					throw new UncheckedIOException(e);
				}
			}
			out.getDependencies().add(project.getDependencies().create("nilgradle.dynamic:"+name));
		}
	}

	private JsonObject toJson(MappingSet mappings, Predicate<Mapping<?, ?>> filter, Set<String> classesToWiden, Set<Member> membersToWiden) {
		JsonObject obj = new JsonObject();
		JsonArray classes = new JsonArray();
		for (TopLevelClassMapping tlcm : mappings.getTopLevelClassMappings()) {
			if (filter.test(tlcm)) {
				JsonObject clazz = toJson(tlcm, filter);
				if (clazz != null) classes.add(clazz);
			}
		}
		obj.put("classes", classes);
		JsonObject widen = new JsonObject();
		if (!classesToWiden.isEmpty()) {
			JsonArray widenClasses = new JsonArray();
			for (String s : classesToWiden) {
				widenClasses.add(mappings.computeClassMapping(s).map(ClassMapping::getFullDeobfuscatedName).orElse(s));
			}
			widen.put("classes", widenClasses);
		}
		if (!membersToWiden.isEmpty()) {
			JsonArray widenMethods = new JsonArray();
			JsonArray widenFields = new JsonArray();
			for (Member m : membersToWiden) {
				JsonObject entry = new JsonObject();
				Optional<? extends ClassMapping<?, ?>> cm = mappings.computeClassMapping(m.owner);
				entry.put("owner", cm.map(ClassMapping::getFullDeobfuscatedName).orElse(m.owner));
				if (m.signature instanceof MethodSignature) {
					MethodSignature ms = (MethodSignature)m.signature;
					entry.put("sig", toString(cm.map(cmm -> cmm.getMethodMapping(ms))
							.orElse(Optional.empty()).map(MethodMapping::getDeobfuscatedSignature).orElseGet(() -> deobf(mappings, ms))));
					widenMethods.add(entry);
				} else if (m.signature instanceof FieldSignature) {
					FieldSignature fs = (FieldSignature)m.signature;
					entry.put("sig", toString(cm.map(cmm -> cmm.computeFieldMapping(fs))
							.orElse(Optional.empty()).map(FieldMapping::getDeobfuscatedSignature).orElseGet(() -> deobf(mappings, fs))));
					widenFields.add(entry);
				}
			}
			if (!widenMethods.isEmpty()) {
				widen.put("methods", widenMethods);
			}
			if (!widenFields.isEmpty()) {
				widen.put("fields", widenFields);
			}
		}
		if (!widen.isEmpty()) {
			obj.put("widen", widen);
		}
		return obj;
	}

	private MethodSignature deobf(MappingSet mappings, MethodSignature ms) {
		return MethodSignature.of(ms.getName(), mappings.deobfuscate(ms.getDescriptor()).toString());
	}

	private FieldSignature deobf(MappingSet mappings, FieldSignature fs) {
		Optional<FieldType> type = fs.getType();
		if (!type.isPresent()) return fs;
		return FieldSignature.of(fs.getName(), mappings.deobfuscate(type.get()).toString());
	}

	private JsonObject toJson(ClassMapping<?, ?> cm, Predicate<Mapping<?, ?>> filter) {
		JsonObject obj = new JsonObject();
		obj.put("from", cm.getObfuscatedName());
		obj.put("to", cm.getDeobfuscatedName());
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

}
