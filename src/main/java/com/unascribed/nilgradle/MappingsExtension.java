package com.unascribed.nilgradle;

import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;

public abstract class MappingsExtension {

	private Project project;
	
	private Configuration targetCfg;
	private Configuration workCfg;
	
	private boolean setWorkAlready = false;
	
	public void target(Object dep) {
		project.getDependencies().add(targetCfg.getName(), dep);
	}
	
	public void work(Object dep) {
		if (setWorkAlready) throw new IllegalStateException("Cannot specify multiple work mappings");
		setWorkAlready = true;
		project.getDependencies().add(workCfg.getName(), dep);
	}

	public void setProject(Project project) {
		this.project = project;
	}
	
	public void setTargetConfiguration(Configuration cfg) {
		targetCfg = cfg;
	}

	public void setWorkConfiguration(Configuration cfg) {
		workCfg = cfg;
	}
	
}
