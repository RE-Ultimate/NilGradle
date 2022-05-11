package com.unascribed.nilgradle;

import org.cadixdev.bombe.type.signature.MethodSignature;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.AnnotationRemapper;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.MethodRemapper;
import org.objectweb.asm.commons.Remapper;

public class MiniAwareClassRemapper extends ClassRemapper {
	private String classTarget;

	public MiniAwareClassRemapper(ClassVisitor classVisitor, Remapper remapper) {
		super(classVisitor, remapper);
	}

	@Override
	protected AnnotationVisitor createAnnotationRemapper(String descriptor, AnnotationVisitor annotationVisitor) {
		switch (descriptor) {
			case "Lnilloader/api/lib/mini/annotation/Patch$Class;":
				return new AnnotationRemapper(Opcodes.ASM9, descriptor, annotationVisitor, remapper) {
					@Override
					public void visit(String name, Object value) {
						if (name.equals("value")) {
							classTarget = value.toString();
							value = remapper.mapType(value.toString().replace('.', '/'));
						}
						super.visit(name, value);
					}
				};
			default:
				return super.createAnnotationRemapper(descriptor, annotationVisitor);
		}
	}

	@Override
	protected MethodVisitor createMethodRemapper(MethodVisitor methodVisitor) {
		return new MethodRemapper(methodVisitor, remapper) {
			@Override
			protected AnnotationVisitor createAnnotationRemapper(String descriptor, AnnotationVisitor annotationVisitor) {
				switch (descriptor) {
					case "Lnilloader/api/lib/mini/annotation/Patch$Method;":
					case "Lnilloader/api/lib/mini/annotation/Patch$Methods;":
						return new AnnotationRemapper(Opcodes.ASM9, descriptor, annotationVisitor, remapper) {
							@Override
							public void visit(String name, Object value) {
								if (name.equals("value")) {
									MethodSignature sig = MethodSignature.of(value.toString());
									if (classTarget == null) {
										NilPlugin.log.info("Found MiniTransformer with no Class annotation; patchers in a transformer like this cannot be remapped! You can ignore this if you are patching unobfuscated code.");
										return;
									}
									value = remapper.mapMethodName(classTarget, sig.getName(), sig.getDescriptor().toString())+remapper.mapMethodDesc(sig.getDescriptor().toString());
								}
								super.visit(name, value);
							}
							
							@Override
							public AnnotationVisitor visitAnnotation(String name, String descriptor) {
								return this;
							}
						};
					default:
						return super.createAnnotationRemapper(descriptor, annotationVisitor);
				}
			}
		};
	}
}