package com.unascribed.nilgradle;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import org.cadixdev.lorenz.MappingSet;
import org.cadixdev.lorenz.io.srg.tsrg.TSrgWriter;
import org.cadixdev.lorenz.model.ClassMapping;
import org.cadixdev.lorenz.model.InnerClassMapping;
import org.cadixdev.lorenz.model.TopLevelClassMapping;
import org.codehaus.groovy.runtime.ResourceGroovyMethods;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.artifacts.ModuleDependency;
import org.gradle.api.artifacts.ModuleVersionIdentifier;
import org.gradle.api.artifacts.ResolvedArtifact;
import com.grack.nanojson.JsonParser;
import com.grack.nanojson.JsonParserException;

public abstract class NilExtension {

	public static final List<Runnable> doAfterClean = new ArrayList<>();
	
	private Project project;
	private String id;
	private boolean standalone;
	
	private static final Pattern SAFE_PATTERN = Pattern.compile("^[a-zA-Z0-9._-]+$");
	
	public void id(String id) {
		this.id = id;
	}
	
	public void standalone(boolean standalone) {
		this.standalone = standalone;
	}
	
	public String getId() {
		return id;
	}
	
	public boolean isStandalone() {
		return standalone;
	}
	
	public Dependency minecraft(String version, String side) throws JsonParserException, IOException {
		return project.getDependencies().create("com.mojang.launcher:"+side+":"+findLauncherDownload(version, side));
	}
	
	public Dependency mojmap(String version, String side) throws JsonParserException, IOException {
		return project.getDependencies().create("com.mojang.launcher:"+side+":"+findLauncherDownload(version, side+"_mappings")+"@txt");
	}
	
	public Dependency forgeFrankenmapping(Object classMappingSpec, Object memberMappingSpec) {
		ModuleDependency classMapping = (ModuleDependency)project.getDependencies().create(classMappingSpec);
		ModuleDependency memberMapping = (ModuleDependency)project.getDependencies().create(memberMappingSpec);
		ResolvedArtifact classMappingA = resolve(classMapping);
		ResolvedArtifact memberMappingA = resolve(memberMapping);
		String filename = "frankenmapping-"+id(classMappingA)+"x"+id(memberMappingA)+"-v2";
		File cache = new File(project.getBuildDir(), "nil/cache/"+filename+".tsrg");
		doAfterClean.add(() -> {
			if (!cache.exists()) {
				cache.getParentFile().mkdirs();
				MappingSet classMappings = NilPlugin.detectAndLoad(classMappingA.getFile());
				MappingSet memberMappings = NilPlugin.detectAndLoad(memberMappingA.getFile());
				MappingSet franken = MappingSet.create();
				for (TopLevelClassMapping tlcm : classMappings.getTopLevelClassMappings()) {
					copy(tlcm, memberMappings, franken);
				}
				try (Writer w = new OutputStreamWriter(new FileOutputStream(cache), StandardCharsets.UTF_8)) {
					new TSrgWriter(w).write(franken);
				} catch (IOException e) {
					e.printStackTrace();
				}
			}
		});
		return project.getDependencies().create("nilgradle.dynamic:"+filename+"@tsrg");
	}

	private String id(ResolvedArtifact artifact) {
		ModuleVersionIdentifier id = artifact.getModuleVersion().getId();
		return id.getGroup()+"."+id.getName()+"-"+id.getVersion();
	}

	private void copy(ClassMapping<?,?> inClass, MappingSet members, MappingSet out) {
		ClassMapping<?, ?> outClass = out.getOrCreateClassMapping(inClass.getFullObfuscatedName());
		outClass.setDeobfuscatedName(inClass.getDeobfuscatedName());
		ClassMapping<?, ?> memberClass = members.getOrCreateClassMapping(inClass.getFullObfuscatedName());
		memberClass.getMethodMappings().forEach(mm -> mm.copy(outClass));
		memberClass.getFieldMappings().forEach(fm -> fm.copy(outClass));
		for (InnerClassMapping icm : inClass.getInnerClassMappings()) {
			copy(icm, members, out);
		}
	}

	private ResolvedArtifact resolve(ModuleDependency dep) {
		Configuration scratch = project.getConfigurations().create("nilInternalScratchConfig"+UUID.randomUUID());
		scratch.getDependencies().add(dep);
		Set<ResolvedArtifact> set = scratch.getResolvedConfiguration().getResolvedArtifacts();
		if (set.isEmpty()) throw new IllegalArgumentException("Dependency has no artifacts");
		if (set.size() > 1) throw new IllegalArgumentException("Dependency has multiple artifacts");
		return set.iterator().next();
	}

	private String findLauncherDownload(String version, String artifact) throws JsonParserException, IOException {
		if (!SAFE_PATTERN.matcher(version).matches()) throw new IllegalArgumentException("Bad version: "+version);
		if (!SAFE_PATTERN.matcher(artifact).matches()) throw new IllegalArgumentException("Bad side: "+version);
		File cache = new File(project.getGradle().getGradleUserHomeDir(), "caches/nilgradle/version-hashes/"+version+"/"+artifact+".sha1");
		String sha1 = null;
		if (cache.exists()) {
			String s = ResourceGroovyMethods.getText(cache, "UTF-8").trim();
			if (s.length() == 40) {
				sha1 = s;
			}
		}
		if (sha1 == null) {
			URL manifest = URI.create("https://piston-meta.mojang.com/mc/game/version_manifest.json").toURL();
			String versionManifest = null;
			try (InputStream in = manifest.openStream()) {
				// I tried to do this using the streaming API for less inefficiency,
				// but there's no docs and I couldn't get it to work
				Map<String, Object> map = JsonParser.object().from(in);
				List<Map<String, Object>> li = (List<Map<String, Object>>) map.get("versions");
				for (Map<String, Object> en : li) {
					if (version.equals(en.get("id"))) {
						versionManifest = (String)en.get("url");
						break;
					}
				}
			}
			if (versionManifest == null) {
				throw new IllegalArgumentException("Cannot find version "+version+" in launcher");
			}
			URL versionManifestUrl = new URL(versionManifest);
			try (InputStream in = versionManifestUrl.openStream()) {
				Map<String, Object> map = JsonParser.object().from(in);
				Map<String, Object> dl = (Map<String, Object>)((Map<String, Object>) map.get("downloads")).get(artifact);
				sha1 = (String)dl.get("sha1");
			}
			cache.getParentFile().mkdirs();
			ResourceGroovyMethods.write(cache, sha1, "UTF-8");
		}
		return sha1;
	}

	public Dependency minecraftArchive(String era, String version) {
		return project.getDependencies().create("org.archive.minecraft-je-"+era+":"+version);
	}

	public Dependency mcpArchive(String version, String mcpVersion) {
		return project.getDependencies().create("org.archive.minecraftcoderpack:"+version+":"+mcpVersion+"@zip");
	}

	public Dependency mcpArchiveSrg(String version, String mcpVersion) {
		return project.getDependencies().create("org.archive.minecraftcoderpack:"+version+":"+mcpVersion+":srg@zip");
	}
	
	public void setProject(Project project) {
		this.project = project;
	}
	
}
