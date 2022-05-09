package com.unascribed.nilgradle;

import org.gradle.api.Project;

public abstract class MappingsExtension {

	private Project project;
	
	private boolean setViaAlready = false;
	private boolean setWorkAlready = false;
	
	public void target(Object dep) {
		project.getDependencies().add("nilInternalTargetMapping", dep);
	}
	
	public void via(Object dep) {
		if (setWorkAlready) throw new IllegalStateException("Cannot specify multiple via mappings");
		setViaAlready = true;
		project.getDependencies().add("nilInternalViaMapping", dep);
	}
	
	public void work(Object dep) {
		if (setWorkAlready) throw new IllegalStateException("Cannot specify multiple work mappings");
		setWorkAlready = true;
		project.getDependencies().add("nilInternalWorkMapping", dep);
	}

	public void setProject(Project project) {
		this.project = project;
	}
	
}
