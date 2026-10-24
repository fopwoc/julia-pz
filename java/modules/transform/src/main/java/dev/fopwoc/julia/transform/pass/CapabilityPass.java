package dev.fopwoc.julia.transform.pass;

import dev.fopwoc.julia.transform.Endpoint;
import java.lang.classfile.ClassFile;
import java.lang.classfile.constantpool.Utf8Entry;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;

/**
 * Converts boolean capability reads to an immutable JDK Set, including stack-map/local types.
 *
 * <p>A capability object may cross fields and methods of the same package: every class of a mod that mentions the
 * type is prepared, so both sides of such a boundary are remapped together. Anything else that declares the type, such
 * as LWJGL, the JDK or the game, would still expect the native object and stays rejected.
 */
public final class CapabilityPass {
    private static final String OWNER = Endpoint.CAPABILITY_BRIDGE.owner();
    private static final String CAPS = "org/lwjgl/opengl/GLCapabilities";
    private static final String DESCRIPTOR = "L" + CAPS + ";";

    private CapabilityPass() {}

    /** Whether a class names the capability type anywhere, even only in its own field or method signatures. */
    public static boolean mentions(byte[] bytes) {
        for (var entry : ClassFile.of().parse(bytes).constantPool())
            if (entry instanceof Utf8Entry text && text.stringValue().contains(CAPS)) return true;
        return false;
    }

    public static ClassNode adapt(ClassNode original) {
        String pkg = pkg(original.name);
        // Work on a copy so rejection never leaves a partially adapted class behind.
        var node = new ClassNode();
        original.accept(node);
        for (var method : node.methods) {
            for (var instruction : method.instructions.toArray()) {
                if (instruction instanceof MethodInsnNode call) {
                    if (call.getOpcode() == Opcodes.INVOKESTATIC
                            && call.owner.equals("org/lwjgl/opengl/GL")
                            && call.name.equals("getCapabilities")
                            && call.desc.equals("()" + DESCRIPTOR)) {
                        call.owner = OWNER;
                        call.name = "current";
                        call.desc = "()Ljava/util/Set;";
                    } else if (call.owner.equals(CAPS)
                            || call.desc.contains(DESCRIPTOR)
                                    && !pkg(call.owner).equals(pkg)) {
                        throw new IllegalArgumentException(
                                "Capability object crosses a method boundary: " + call.owner + "." + call.name);
                    }
                } else if (instruction instanceof FieldInsnNode field) {
                    if (field.getOpcode() == Opcodes.GETSTATIC
                            && field.owner.equals("org/lwjglx/opengl/Display")
                            && field.name.equals("capabilities")
                            && field.desc.equals(DESCRIPTOR)) {
                        method.instructions.set(
                                field,
                                new MethodInsnNode(Opcodes.INVOKESTATIC, OWNER, "current", "()Ljava/util/Set;", false));
                    } else if (field.owner.equals(CAPS)) {
                        if (field.getOpcode() != Opcodes.GETFIELD
                                || !field.desc.equals("Z")
                                || !(field.name.startsWith("GL_") || field.name.matches("OpenGL[1-4][0-6]"))) {
                            throw new IllegalArgumentException(
                                    "Unsupported capability access: " + field.name + field.desc);
                        }
                        var query = new InsnList();
                        query.add(new LdcInsnNode(field.name));
                        query.add(new MethodInsnNode(
                                Opcodes.INVOKEINTERFACE, "java/util/Set", "contains", "(Ljava/lang/Object;)Z", true));
                        method.instructions.insertBefore(field, query);
                        method.instructions.remove(field);
                    } else if (field.desc.contains(DESCRIPTOR)
                            && !pkg(field.owner).equals(pkg)) {
                        throw new IllegalArgumentException("Capability object crosses a field boundary");
                    }
                } else if (instruction instanceof LdcInsnNode constant) {
                    checkConstant(constant.cst, pkg);
                } else if (instruction instanceof InvokeDynamicInsnNode dynamic) {
                    checkConstant(dynamic.bsm, pkg);
                    for (Object argument : dynamic.bsmArgs) checkConstant(argument, pkg);
                } else if (instruction instanceof TypeInsnNode type
                        && type.desc.equals(CAPS)
                        && type.getOpcode() != Opcodes.CHECKCAST
                        && type.getOpcode() != Opcodes.INSTANCEOF) {
                    throw new IllegalArgumentException("Native capability construction is unsupported");
                }
            }
        }
        var mapped = new ClassNode();
        node.accept(new ClassRemapper(mapped, new Remapper(Opcodes.ASM9) {
            @Override
            public String map(String type) {
                return type.equals(CAPS) ? "java/util/Set" : type;
            }
        }));
        return mapped;
    }

    private static String pkg(String internalName) {
        return internalName.substring(0, Math.max(0, internalName.lastIndexOf('/')));
    }

    private static void checkConstant(Object value, String pkg) {
        // Same-package handles, such as a record's generated component accessors, are remapped with their targets.
        if (value instanceof Handle handle
                && (handle.getOwner().equals(CAPS)
                        || handle.getDesc().contains(DESCRIPTOR)
                                && !pkg(handle.getOwner()).equals(pkg))) {
            throw new IllegalArgumentException("Capability method/field handles require a dedicated bridge");
        }
        if (value instanceof Type type && type.getDescriptor().contains(CAPS)) {
            throw new IllegalArgumentException("Native capability class literal or method type is unsupported");
        }
        if (value instanceof ConstantDynamic dynamic) {
            if (dynamic.getDescriptor().contains(DESCRIPTOR))
                throw new IllegalArgumentException("Native capability dynamic constant");
            checkConstant(dynamic.getBootstrapMethod(), pkg);
            for (int i = 0; i < dynamic.getBootstrapMethodArgumentCount(); i++)
                checkConstant(dynamic.getBootstrapMethodArgument(i), pkg);
        }
    }
}
