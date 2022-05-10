package com.unascribed.nilgradle;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Optional;
import java.util.Set;

import org.cadixdev.bombe.analysis.InheritanceProvider;
import org.cadixdev.bombe.type.signature.FieldSignature;
import org.cadixdev.bombe.type.signature.MethodSignature;
import org.cadixdev.lorenz.MappingSet;
import org.cadixdev.lorenz.model.ClassMapping;
import org.cadixdev.lorenz.model.Mapping;
import org.cadixdev.lorenz.model.MemberMapping;
import org.objectweb.asm.commons.Remapper;

public class TrackingLorenzRemapper extends Remapper {

	private final MappingSet mappings;
	private final InheritanceProvider inheritanceProvider;
	
	private final Set<Mapping<?, ?>> usedMappings = Collections.newSetFromMap(new IdentityHashMap<>());

	public TrackingLorenzRemapper(final MappingSet mappings, final InheritanceProvider inheritanceProvider) {
		this.mappings = mappings;
		this.inheritanceProvider = inheritanceProvider;
	}
	
	private <T extends Mapping<?, ?>> Optional<T> track(Optional<T> t) {
		t.ifPresent(this::track);
		return t;
	}
	
	private void track(Mapping<?, ?> m) {
		usedMappings.add(m);
		if (m instanceof MemberMapping<?, ?>) {
			@SuppressWarnings("unchecked")
			MemberMapping<?, ? extends Mapping<?, ?>> mm = (MemberMapping<?, ? extends Mapping<?, ?>>)m;
			track(mm.getParent());
		}
	}
	
	public Set<Mapping<?, ?>> getUsedMappings() {
		return usedMappings;
	}
	
	@Override
	public String map(final String typeName) {
		return track(this.mappings.computeClassMapping(typeName))
				.map(Mapping::getFullDeobfuscatedName)
				.orElse(typeName);
	}

	@Override
	public String mapInnerClassName(final String name, final String ownerName, final String innerName) {
		return track(this.mappings.computeClassMapping(name))
				.map(Mapping::getDeobfuscatedName)
				.orElse(innerName);
	}

	private Optional<? extends ClassMapping<?, ?>> getCompletedClassMapping(final String owner) {
		final Optional<? extends ClassMapping<?, ?>> mapping = this.mappings.getClassMapping(owner);
		mapping.ifPresent(m -> m.complete(this.inheritanceProvider));
		return mapping;
	}

	@Override
	public String mapFieldName(final String owner, final String name, final String desc) {
		return track(this.getCompletedClassMapping(owner))
				.map(m -> track(m.computeFieldMapping(FieldSignature.of(name, desc))))
				.orElse(Optional.empty())
				.map(Mapping::getDeobfuscatedName)
				.orElse(name);
	}

	@Override
	public String mapMethodName(final String owner, final String name, final String desc) {
		return track(this.getCompletedClassMapping(owner))
				.map(m -> track(m.getMethodMapping(MethodSignature.of(name, desc))))
				.orElse(Optional.empty())
				.map(Mapping::getDeobfuscatedName)
				.orElse(name);
	}

}