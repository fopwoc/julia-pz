package dev.julia.transform.prepare;

import static org.junit.jupiter.api.Assertions.*;

import dev.julia.runtime.RenderSession;
import dev.julia.transform.TestFrames;
import dev.julia.transform.pass.DispatchPass;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.InvocationTargetException;
import java.util.function.IntSupplier;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

class RuntimePreparationTest {
    @Test
    void executesOriginalLwjglCallThroughIsolatedTypedTarget() throws Exception {
        var result = new RuntimePreparation().prepare(fixture("glGetError", "()I"));
        assertTrue(result.ready(), result.blockers().toString());
        assertEquals(1, result.calls());
        var loader = new ClassLoader(getClass().getClassLoader()) {
            Class<?> define(byte[] bytes) {
                return defineClass(null, bytes, 0, bytes.length);
            }
        };
        var type = loader.define(result.requireBytecode());
        var session = RenderSession.open(
                (owner, name, signature) -> {
                    assertEquals("com/gtnewhorizons/angelica/glsm/GLStateManager", owner);
                    assertEquals("glGetError", name);
                    return MethodHandles.constant(int.class, 1234);
                },
                TestFrames.idle());
        try {
            assertEquals(1234, type.getMethod("draw").invoke(null));
        } finally {
            session.close();
        }
        var error = assertThrows(
                InvocationTargetException.class, () -> type.getMethod("draw").invoke(null));
        assertInstanceOf(IllegalStateException.class, error.getCause());
    }

    @Test
    void blocksUnredirectedNativeCallsBeforeExposingExecutableBytes() throws Exception {
        var result = new RuntimePreparation().prepare(fixture("glUnknownJuliaTest", "()I"));
        assertFalse(result.ready());
        assertTrue(result.blockers().getFirst().contains("NATIVE_OPENGL"));
        assertThrows(IllegalStateException.class, result::requireBytecode);
    }

    @Test
    void rejectsNonJdkTypesAcrossLoaderBoundary() {
        var node = new ClassNode();
        var method = new MethodNode();
        node.methods.add(method);
        method.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "com/gtnewhorizons/angelica/glsm/GLStateManager",
                "callback",
                "(Lorg/lwjgl/opengl/GLDebugMessageCallback;)V",
                false));
        assertThrows(IllegalArgumentException.class, () -> DispatchPass.adapt(node));
    }

    @Test
    void blocksNativeCapabilityFunctionPointers() throws Exception {
        byte[] bytes = fixture("glGetError", "()I");
        var node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        var method = node.methods.getFirst();
        method.instructions.insert(new InsnNode(Opcodes.POP2));
        method.instructions.insert(
                new FieldInsnNode(Opcodes.GETFIELD, "org/lwjgl/opengl/GLCapabilities", "glGenTextures", "J"));
        method.instructions.insert(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "org/lwjgl/opengl/GL",
                "getCapabilities",
                "()Lorg/lwjgl/opengl/GLCapabilities;",
                false));
        var writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        var result = new RuntimePreparation().prepare(writer.toByteArray());
        assertFalse(result.ready());
        assertTrue(result.blockers().stream().anyMatch(s -> s.contains("GL.getCapabilities")));
    }

    @Test
    void routesMethodReferenceHelpersDeclaredOnAnInterface() throws Exception {
        var writer = new ClassWriter(0);
        writer.visit(
                Opcodes.V25,
                Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT,
                "zombie/InterfaceFixture",
                null,
                "java/lang/Object",
                null);
        var method = writer.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "supplier", "()Ljava/util/function/IntSupplier;", null, null);
        method.visitCode();
        method.visitInvokeDynamicInsn(
                "getAsInt",
                "()Ljava/util/function/IntSupplier;",
                new Handle(
                        Opcodes.H_INVOKESTATIC,
                        "java/lang/invoke/LambdaMetafactory",
                        "metafactory",
                        "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;",
                        false),
                Type.getMethodType("()I"),
                new Handle(
                        Opcodes.H_INVOKESTATIC,
                        "com/gtnewhorizons/angelica/glsm/GLStateManager",
                        "glGetError",
                        "()I",
                        false),
                Type.getMethodType("()I"));
        method.visitInsn(Opcodes.ARETURN);
        method.visitMaxs(1, 0);
        method.visitEnd();
        writer.visitEnd();
        var result = new RuntimePreparation().prepare(writer.toByteArray());
        assertTrue(result.ready(), result.blockers().toString());
        byte[] bytes = result.requireBytecode();
        var type = new ClassLoader(getClass().getClassLoader()) {
            Class<?> define() {
                return defineClass(null, bytes, 0, bytes.length);
            }
        }.define();
        var session = RenderSession.open(
                (owner, name, signature) -> MethodHandles.constant(int.class, 77), TestFrames.idle());
        try {
            var supplier = (IntSupplier) type.getMethod("supplier").invoke(null);
            assertEquals(77, supplier.getAsInt());
        } finally {
            session.close();
        }
    }

    private static byte[] fixture(String name, String descriptor) {
        var writer = new ClassWriter(0);
        writer.visit(Opcodes.V25, Opcodes.ACC_PUBLIC, "zombie/JuliaFixture", null, "java/lang/Object", null);
        var method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "draw", "()I", null, null);
        method.visitCode();
        method.visitMethodInsn(Opcodes.INVOKESTATIC, "org/lwjgl/opengl/GL11", name, descriptor, false);
        method.visitInsn(Opcodes.IRETURN);
        method.visitMaxs(1, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }
}
