package com.unascribed.nilgradle;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.function.Consumer;
import java.util.Optional;
import java.util.Set;

import org.cadixdev.bombe.asm.jar.ClassProvider;
import org.cadixdev.bombe.type.ArrayType;
import org.cadixdev.bombe.type.FieldType;
import org.cadixdev.bombe.type.MethodDescriptor;
import org.cadixdev.bombe.type.ObjectType;
import org.cadixdev.bombe.type.Type;
import org.cadixdev.bombe.type.signature.FieldSignature;
import org.cadixdev.bombe.type.signature.MethodSignature;
import org.cadixdev.lorenz.MappingSet;
import org.cadixdev.lorenz.model.FieldMapping;
import org.cadixdev.lorenz.model.MethodMapping;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.AnnotationRemapper;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.MethodRemapper;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

public class NilGradleClassRemapper extends ClassRemapper {
	private static final boolean DEBUG = false;
	
	private final MappingSet mappings;
	private String classTarget;
	private String className;
	private String classPackage;
	private String classSuper;
	private final ClassProvider provider;
	private final Set<String> classesToWiden;
	private final Set<Member> membersToWiden;
	private final Map<String, ClassNode> classCache = new HashMap<>();

	public NilGradleClassRemapper(MappingSet mappings, ClassVisitor classVisitor, Remapper remapper, ClassProvider provider, Set<String> classesToWiden, Set<Member> membersToWiden) {
		super(classVisitor, remapper);
		this.mappings = mappings;
		this.provider = provider;
		this.classesToWiden = classesToWiden;
		this.membersToWiden = membersToWiden;
	}
	
	@Override
	public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
		className = name;
		int slash = name.lastIndexOf('/');
		if (slash == -1) {
			classPackage = "";
		} else {
			classPackage = name.substring(0, slash);
		}
		classSuper = superName;
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
	public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
		scanAndMemoizeRemapMethod(className, name, descriptor);
		return super.visitMethod(access, name, descriptor, signature, exceptions);
	}

	private void scanAndMemoizeRemapMethod(String owner, String name, String descriptor) {
		Map<MethodSignature, String> superSigs = new HashMap<>();
		Map<MethodSignature, String> rawSuperSigs = new HashMap<>();
		String check = owner;
		while (!"java/lang/Object".equals(check)) {
			ClassNode node = classCache.computeIfAbsent(check, provider::getAsNode);
			if (node == null) break;
			scanInterfaces(name, descriptor, superSigs, node);
			findMethod(name, descriptor, superSigs, node);
			check = node.superName;
		}
		rawSuperSigs.putAll(superSigs);
		boolean conflict = superSigs.size() > 1;
		String conflictMessage = null;
		if (superSigs.size() == 2) {
			Iterator<MethodSignature> i = superSigs.keySet().iterator();
			MethodSignature a = i.next();
			MethodSignature b = i.next();
			if (a.getName().length() > b.getName().length()) {
				superSigs.remove(b);
				conflictMessage = "Boldly assuming you want the longer one!";
			} else if (b.getName().length() > a.getName().length()) {
				superSigs.remove(a);
				conflictMessage = "Boldly assuming you want the longer one!";
			}
		}
		if (superSigs.size() > 1) {
			conflictMessage = "The method will not be remapped.";
		} else if (superSigs.size() == 1) {
			MethodMapping mm = mappings.getOrCreateClassMapping(owner).getOrCreateMethodMapping(name, descriptor);
			MethodSignature sig = superSigs.keySet().iterator().next();
			mm.setDeobfuscatedName(sig.getName());
			// track the mapping
			remapper.mapMethodName(owner, name, descriptor);
		}
		if (conflict) {
			Consumer<String> log = superSigs.size() > 1 ? NilPlugin.log::error : NilPlugin.log::warn;
			log.accept((superSigs.size() > 1 ? "UNRESOLVABLE " : "")+"CONFLICT: "+owner+"."+name+descriptor+" has multiple possible obfuscated signatures:");
			for (Entry<MethodSignature, String> en : rawSuperSigs.entrySet()) {
				log.accept(" - "+en.getKey().toJvmsIdentifier()+" defined in "+en.getValue());
			}
			log.accept(conflictMessage);
		}
	}

	private void scanAndMemoizeRemapField(String owner, String name, String descriptor) {
		Map<FieldSignature, String> superSigs = new HashMap<>();
		String check = owner;
		while (!"java/lang/Object".equals(check)) {
			ClassNode node = classCache.computeIfAbsent(check, provider::getAsNode);
			if (node == null) break;
			findField(name, descriptor, superSigs, node);
			check = node.superName;
		}
		if (superSigs.size() > 1) {
			System.err.println("UNRESOLVABLE CONFLICT: "+owner+"#"+name+":"+descriptor+" has multiple possible obfuscated signatures:");
			for (Entry<FieldSignature, String> en : superSigs.entrySet()) {
				System.err.println(" - "+en.getKey().toJvmsIdentifier()+" defined in "+en.getValue());
			}
			System.err.println("The field will not be remapped.");
		} else if (superSigs.size() == 1) {
			FieldMapping mm = mappings.getOrCreateClassMapping(owner).getOrCreateFieldMapping(name, descriptor);
			FieldSignature sig = superSigs.keySet().iterator().next();
			mm.setDeobfuscatedName(sig.getName());
			// track the mapping
			remapper.mapFieldName(owner, name, descriptor);
		}
	}

	private void scanInterfaces(String name, String descriptor, Map<MethodSignature, String> superSigs, ClassNode node) {
		for (String iface : node.interfaces) {
			ClassNode ifaceNode = classCache.computeIfAbsent(iface, provider::getAsNode);
			if (ifaceNode != null) {
				findMethod(name, descriptor, superSigs, ifaceNode);
				scanInterfaces(name, descriptor, superSigs, ifaceNode);
			}
		}
	}

	private void findMethod(String name, String descriptor, Map<MethodSignature, String> superSigs, ClassNode node) {
		for (MethodNode mn : node.methods) {
			if (mn.name.equals(name) && mn.desc.equals(descriptor)) {
				MethodMapping mapping = mappings.computeClassMapping(node.name).map(cm -> cm.getMethodMapping(name, descriptor)).orElse(Optional.empty()).orElse(null);
				if (mapping != null) {
					superSigs.put(mapping.getDeobfuscatedSignature(), node.name);
				}
			}
		}
	}

	private void findField(String name, String descriptor, Map<FieldSignature, String> superSigs, ClassNode node) {
		FieldSignature sig = FieldSignature.of(name, descriptor);
		for (FieldNode fn : node.fields) {
			if (fn.name.equals(name) && fn.desc.equals(descriptor)) {
				FieldMapping mapping = mappings.computeClassMapping(node.name).map(cm -> cm.computeFieldMapping(sig)).orElse(Optional.empty()).orElse(null);
				if (mapping != null) {
					superSigs.put(mapping.getDeobfuscatedSignature(), node.name);
				}
			}
		}
	}

	@Override
	protected MethodVisitor createMethodRemapper(MethodVisitor methodVisitor) {
		return new MethodRemapper(methodVisitor, remapper) {
			@Override
			protected AnnotationVisitor createAnnotationRemapper(String descriptor, AnnotationVisitor annotationVisitor) {
				if (descriptor != null) {
					switch (descriptor) {
						case "Lnilloader/api/lib/mini/annotation/Patch$Method;":
						case "Lnilloader/api/lib/mini/annotation/Patch$Methods;":
							return new AnnotationRemapper(Opcodes.ASM9, descriptor, annotationVisitor, remapper) {
								@Override
								public void visit(String name, Object value) {
									if (name.equals("value")) {
										try {
											MethodSignature sig = MethodSignature.of(value.toString());
											if (classTarget == null) {
												NilPlugin.log.info("Found MiniTransformer with no Class annotation; patchers in a transformer like this cannot be remapped! You can ignore this if you are patching unobfuscated code.");
												return;
											}
											value = remapper.mapMethodName(classTarget, sig.getName(), sig.getDescriptor().toString()) + remapper.mapMethodDesc(sig.getDescriptor().toString());
										} catch (IllegalArgumentException | IndexOutOfBoundsException e) {
											NilPlugin.log.error("@Patch.Method values must be full binary descriptors; found \"" + value + "\" in " + className + " - it will not be remapped");
										}
									}
									super.visit(name, value);
								}

								@Override
								public AnnotationVisitor visitAnnotation(String name, String subDescriptor) {
									return this;
								}

								@Override
								public AnnotationVisitor visitArray(String name) {
									return this;
								}
							};
					}
				}

				return super.createAnnotationRemapper(descriptor, annotationVisitor);
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
						try {
							if (mappings.computeClassMapping(value.toString()).isPresent()) {
								stage = 1;
								owner = value.toString();
								remappedOwner = remapper.mapType(value.toString());
								return;
							}
						} catch (IllegalArgumentException | IndexOutOfBoundsException e) {
							// ignore
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
						} catch (IllegalArgumentException | IllegalStateException | IndexOutOfBoundsException e) {
							try {
								FieldSignature.of(subject, desc);
								remappedSubject = remapper.mapFieldName(owner, subject, desc);
								remappedDesc = remapper.mapDesc(desc);
							} catch (IllegalArgumentException | IllegalStateException | IndexOutOfBoundsException e2) {
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

			private boolean canAccess(String owner, boolean member, int access) {
				if ((access & Opcodes.ACC_PUBLIC) != 0) {
					if (DEBUG) System.out.println("    public");
					return true;
				}
				if ((access & Opcodes.ACC_PRIVATE) != 0) {
					if (DEBUG) System.out.println("    private");
					return owner.equals(className);
				}
				int slash = owner.lastIndexOf('/');
				String pkg;
				if (slash != -1) {
					pkg = owner.substring(0, slash);
				} else {
					pkg = "";
				}
				if ((access & Opcodes.ACC_PROTECTED) != 0) {
					if (DEBUG) System.out.println("    protected");
					if (!member) {
						if (pkg.equals(classPackage)) {
							return true;
						}
					} else {
						if (owner.equals(classSuper)) {
							return true;
						}
						String check = classSuper;
						while (!"java/lang/Object".equals(check)) {
							if (owner.equals(check)) {
								return true;
							}
							ClassNode cn = classCache.computeIfAbsent(check, provider::getAsNode);
							if (cn == null) break;
							check = cn.superName;
						}
					}
					return owner.equals(className);
				}
				if (DEBUG) System.out.println("    package");
				if (pkg.equals(classPackage)) {
					return true;
				}
				return owner.equals(className);
			}
			
			private ClassNode checkWidening(String owner) {
				ClassNode cn = classCache.computeIfAbsent(owner, (name) -> provider.getAsNode(name, ClassReader.SKIP_CODE));
				if (DEBUG) System.out.println(owner);
				if (cn == null) {
					if (DEBUG) System.out.println("  NOT FOUND");
					return null;
				}
				if (!canAccess(owner, false, cn.access)) {
					if (DEBUG) System.out.println("  widening");
					classesToWiden.add(owner);
				}
				return cn;
			}
			
			private void checkWideningField(String owner, String name, String desc) {
				FieldType ft = unwrap(FieldType.of(desc));
				if (ft instanceof ObjectType) {
					checkWidening(((ObjectType) ft).getClassName().replace('.', '/'));
				}
				ClassNode cn = checkWidening(owner);
				if (cn == null) return;
				if (DEBUG) System.out.println(" ?#"+name+":"+desc);
				if (checkWideningFieldIn(cn, name, desc)) return;
				String check = cn.superName;
				while (!"java/lang/Object".equals(check)) {
					ClassNode superNode = classCache.computeIfAbsent(check, provider::getAsNode);
					if (superNode == null) break;
					if (checkWideningFieldIn(superNode, name, desc)) return;
					check = superNode.superName;
				}
				if (DEBUG) System.out.println("    NOT FOUND");
			}

			private boolean checkWideningFieldIn(ClassNode cn, String name, String desc) {
				for (FieldNode fn : cn.fields) {
					if (fn.name.equals(name) && fn.desc.equals(desc)) {
						if (DEBUG) System.out.println("    found");
						if (!canAccess(cn.name, true, fn.access)) {
							if (DEBUG) System.out.println("    widening");
							membersToWiden.add(new Member(cn.name, FieldSignature.of(name, desc)));
						}
						return true;
					}
				}
				return false;
			}

			private void checkWideningMethod(String owner, String name, String desc) {
				MethodDescriptor md = MethodDescriptor.of(desc);
				for (FieldType ft : md.getParamTypes()) {
					ft = unwrap(ft);
					if (ft instanceof ObjectType) {
						checkWidening(((ObjectType) ft).getClassName().replace('.', '/'));
					}
				}
				Type rtrn = unwrap(md.getReturnType());
				if (rtrn instanceof ObjectType) {
					checkWidening(((ObjectType) rtrn).getClassName().replace('.', '/'));
				}
				ClassNode cn = checkWidening(owner);
				if (cn == null) return;
				if (DEBUG) System.out.println(" ?::"+name+desc);
				if (checkWideningMethodIn(cn, name, desc)) return;
				String check = cn.superName;
				while (!"java/lang/Object".equals(check)) {
					ClassNode superNode = classCache.computeIfAbsent(check, provider::getAsNode);
					if (superNode == null) break;
					if (checkWideningMethodIn(superNode, name, desc)) return;
					check = superNode.superName;
				}
				if (DEBUG) System.out.println("    NOT FOUND");
			}

			private <T extends Type> T unwrap(T ft) {
				if (ft instanceof ArrayType) {
					return (T) unwrap(((ArrayType)ft).getComponent());
				}
				return ft;
			}

			private boolean checkWideningMethodIn(ClassNode cn, String name, String desc) {
				for (MethodNode mn : cn.methods) {
					if (mn.name.equals(name) && mn.desc.equals(desc)) {
						if (DEBUG) System.out.println("    found");
						if (!canAccess(cn.name, true, mn.access)) {
							if (DEBUG) System.out.println("    widening");
							membersToWiden.add(new Member(cn.name, MethodSignature.of(name, desc)));
						}
						return true;
					}
				}
				return false;
			}

			@Override
			public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
				resetLdcStage();
				checkWideningField(owner, name, descriptor);
				scanAndMemoizeRemapField(owner, name, descriptor);
				super.visitFieldInsn(opcode, owner, name, descriptor);
			}

			@Override
			public void visitMethodInsn(int opcodeAndSource, String owner, String name, String descriptor, boolean isInterface) {
				resetLdcStageOrEmitRemappedOwner(owner, name);
				checkWideningMethod(owner, name, descriptor);
				scanAndMemoizeRemapMethod(owner, name, descriptor);
				super.visitMethodInsn(opcodeAndSource, owner, name, descriptor, isInterface);
			}

			@Override
			public void visitInvokeDynamicInsn(String name, String descriptor, Handle bootstrapMethodHandle, Object... bootstrapMethodArguments) {
				resetLdcStage();
				checkWideningMethod(owner, name, descriptor);
				scanAndMemoizeRemapMethod(owner, name, descriptor);
				checkWideningMethod(bootstrapMethodHandle.getOwner(), bootstrapMethodHandle.getName(), bootstrapMethodHandle.getDesc());
				scanAndMemoizeRemapMethod(bootstrapMethodHandle.getOwner(), bootstrapMethodHandle.getName(), bootstrapMethodHandle.getDesc());
				for (Object arg : bootstrapMethodArguments) {
					if (!(arg instanceof Handle)) continue;
					Handle argHandle = (Handle) arg;
					checkWideningMethod(argHandle.getOwner(), argHandle.getName(), argHandle.getDesc());
					scanAndMemoizeRemapMethod(argHandle.getOwner(), argHandle.getName(), argHandle.getDesc());
				}
				super.visitInvokeDynamicInsn(name, descriptor, bootstrapMethodHandle, bootstrapMethodArguments);
			}

			@Override
			public void visitTypeInsn(int opcode, String type) {
				resetLdcStage();
				checkWidening(type);
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