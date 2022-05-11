package com.unascribed.nilgradle;

import org.cadixdev.bombe.type.MethodDescriptor;
import org.cadixdev.bombe.type.signature.FieldSignature;
import org.cadixdev.bombe.type.signature.MethodSignature;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.AnnotationRemapper;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.MethodRemapper;
import org.objectweb.asm.commons.Remapper;

public class MiniAwareClassRemapper extends ClassRemapper {
	private String classTarget;
	private String className;

	public MiniAwareClassRemapper(ClassVisitor classVisitor, Remapper remapper) {
		super(classVisitor, remapper);
	}

	@Override
	public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
		className = name;
		super.visit(version, access, name, signature, superName, interfaces);
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
			
			private int stage = 0;
			private String owner;
			private String remappedOwner;
			private String subject;
			private Integer arg;
			
			@Override
			public void visitLdcInsn(Object value) {
				if (value instanceof String) {
					if (stage == 0) {
						String newType = remapper.mapType(value.toString());
						if (!value.equals(newType)) {
							stage = 1;
							owner = value.toString();
							remappedOwner = newType;
							return;
						}
					} else if (stage == 1) {
						subject = value.toString();
						stage = 2;
						return;
					} else if (stage == 2) {
						String remappedSubject = subject;
						String desc = value.toString();
						String remappedDesc = desc;
						try {
							MethodDescriptor.of(desc);
							remappedSubject = remapper.mapMethodName(owner, subject, desc);
							remappedDesc = remapper.mapMethodDesc(desc);
						} catch (IllegalArgumentException | IllegalStateException e) {
							try {
								FieldSignature.of(subject, desc);
								remappedSubject = remapper.mapFieldName(owner, subject, desc);
								remappedDesc = remapper.mapDesc(desc);
							} catch (IllegalArgumentException | IllegalStateException e2) {
							}
						}
						super.visitLdcInsn(remappedOwner);
						super.visitLdcInsn(remappedSubject);
						super.visitLdcInsn(remappedDesc);
						owner = null;
						remappedOwner = null;
						subject = null;
						stage = 0;
						return;
					} else {
						resetLdcStage();
					}
				} else {
					if (value instanceof Integer && stage == 1) {
						arg = (Integer)value;
						stage = -1;
					} else {
						resetLdcStage();
					}
				}
				super.visitLdcInsn(value);
			}

			private void resetLdcStage() {
				stage = 0;
				if (owner != null) {
					super.visitLdcInsn(owner);
					owner = null;
					remappedOwner = null;
				}
				if (arg != null) {
					super.visitLdcInsn(arg);
					arg = null;
				}
				if (subject != null) {
					super.visitLdcInsn(subject);
					subject = null;
				}
			}

			private void resetLdcStageOrEmitRemappedOwner(String methodOwner, String name) {
				if (methodOwner.equals(className) && owner != null) {
					switch (name) {
						case "NEW":
						case "ANEWARRAY":
						case "CHECKCAST":
						case "INSTANCEOF":
							super.visitLdcInsn(remappedOwner);
							owner = null;
							remappedOwner = null;
							break;
						case "MULTIANEWARRAY":
							if (arg != null) {
								super.visitLdcInsn(remappedOwner);
								super.visitLdcInsn(arg);
								owner = null;
								remappedOwner = null;
								arg = null;
							}
							break;
					}
				}
				resetLdcStage();
			}

			@Override
			public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
				resetLdcStage();
				super.visitFieldInsn(opcode, owner, name, descriptor);
			}

			@Override
			public void visitMethodInsn(int opcodeAndSource, String owner, String name, String descriptor, boolean isInterface) {
				resetLdcStageOrEmitRemappedOwner(owner, name);
				super.visitMethodInsn(opcodeAndSource, owner, name, descriptor, isInterface);
			}

			@Override
			public void visitInvokeDynamicInsn(String name, String descriptor, Handle bootstrapMethodHandle, Object... bootstrapMethodArguments) {
				resetLdcStage();
				super.visitInvokeDynamicInsn(name, descriptor, bootstrapMethodHandle, bootstrapMethodArguments);
			}

			@Override
			public void visitTypeInsn(int opcode, String type) {
				resetLdcStage();
				super.visitTypeInsn(opcode, type);
			}

			@Override
			public void visitInsn(int opcode) {
				resetLdcStage();
				super.visitInsn(opcode);
			}

			@Override
			public void visitIntInsn(int opcode, int operand) {
				resetLdcStage();
				super.visitIntInsn(opcode, operand);
			}

			@Override
			public void visitVarInsn(int opcode, int var) {
				resetLdcStage();
				super.visitVarInsn(opcode, var);
			}

			@Override
			public void visitJumpInsn(int opcode, Label label) {
				resetLdcStage();
				super.visitJumpInsn(opcode, label);
			}

			@Override
			public void visitIincInsn(int var, int increment) {
				resetLdcStage();
				super.visitIincInsn(var, increment);
			}

			@Override
			public void visitTableSwitchInsn(int min, int max, Label dflt, Label... labels) {
				resetLdcStage();
				super.visitTableSwitchInsn(min, max, dflt, labels);
			}

			@Override
			public void visitLookupSwitchInsn(Label dflt, int[] keys, Label[] labels) {
				resetLdcStage();
				super.visitLookupSwitchInsn(dflt, keys, labels);
			}

		};
	}
}