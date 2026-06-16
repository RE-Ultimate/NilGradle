package com.unascribed.nilgradle;

import org.cadixdev.lorenz.MappingSet;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.artifacts.ModuleDependency;

public abstract class MappingsExtension {

	private Project project;
	
	private boolean setViaAlready = false;
	private boolean setWorkAlready = false;

	private Side side = Side.JOINED;
	private final MappingSet customMappings = MappingSet.create();
	
	public void target(Object dep) {
		noTransitive(project.getDependencies().add("nilInternalTargetMapping", dep));
	}
	
	public void altTarget(Object dep) {
		noTransitive(project.getDependencies().add("nilInternalAltTargetMapping", dep));
	}
	
	public void via(Object dep) {
		if (setViaAlready) throw new IllegalStateException("Cannot specify multiple via mappings");
		setViaAlready = true;
		noTransitive(project.getDependencies().add("nilInternalViaMapping", dep));
	}
	
	public void work(Object dep) {
		if (setWorkAlready) throw new IllegalStateException("Cannot specify multiple work mappings");
		setWorkAlready = true;
		noTransitive(project.getDependencies().add("nilInternalWorkMapping", dep));
	}

	public void side(Object side) {
		this.side = Side.parse(side);
	}
	
	private void noTransitive(Dependency d) {
		if (d instanceof ModuleDependency) {
			((ModuleDependency)d).setTransitive(false);
		}
	}

	public void mapClass(String obf, String deobf) {
		customMappings.getOrCreateClassMapping(obf).setDeobfuscatedName(deobf);
	}

	public MappingSet getCustomMappings() {
		return customMappings;
	}

	public void setProject(Project project) {
		this.project = project;
	}

	public Side getSide() {
		return side;
	}

}
