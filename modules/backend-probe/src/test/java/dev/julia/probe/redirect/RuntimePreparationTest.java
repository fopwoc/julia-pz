package dev.julia.probe.redirect;

import dev.julia.runtime.RenderSession;
import java.lang.invoke.MethodHandles;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import static org.junit.jupiter.api.Assertions.*;

class RuntimePreparationTest {
    @Test void executesOriginalLwjglCallThroughIsolatedTypedTarget() throws Exception {
        var result = new RuntimePreparation().prepare(fixture("glGetError", "()I"));
        assertTrue(result.ready(), result.blockers().toString());
        assertEquals(1, result.calls());
        var loader = new ClassLoader(getClass().getClassLoader()) {
            Class<?> define(byte[] bytes) { return defineClass(null, bytes, 0, bytes.length); }
        };
        var type = loader.define(result.requireBytecode());
        var session = RenderSession.open((owner, name, signature) -> {
            assertEquals("com/gtnewhorizons/angelica/glsm/GLStateManager", owner);
            assertEquals("glGetError", name);
            return MethodHandles.constant(int.class, 1234);
        }, frames());
        try { assertEquals(1234, type.getMethod("draw").invoke(null)); }
        finally { session.close(); }
        var error = assertThrows(java.lang.reflect.InvocationTargetException.class, () -> type.getMethod("draw").invoke(null));
        assertInstanceOf(IllegalStateException.class, error.getCause());
    }

    @Test void blocksUnredirectedNativeCallsBeforeExposingExecutableBytes() throws Exception {
        var result = new RuntimePreparation().prepare(fixture("glUnknownJuliaTest", "()I"));
        assertFalse(result.ready());
        assertTrue(result.blockers().getFirst().contains("NATIVE_OPENGL"));
        assertThrows(IllegalStateException.class, result::requireBytecode);
    }

    @Test void rejectsNonJdkTypesAcrossLoaderBoundary() {
        var node = new org.objectweb.asm.tree.ClassNode();
        var method = new org.objectweb.asm.tree.MethodNode();
        node.methods.add(method);
        method.instructions.add(new org.objectweb.asm.tree.MethodInsnNode(Opcodes.INVOKESTATIC,
                "com/gtnewhorizons/angelica/glsm/GLStateManager", "callback", "(Lorg/lwjgl/opengl/GLDebugMessageCallback;)V", false));
        assertThrows(IllegalArgumentException.class, () -> RuntimeCalls.adapt(node));
    }

    @Test void blocksNativeCapabilityFunctionPointers() throws Exception {
        byte[] bytes = fixture("glGetError", "()I");
        var node = new org.objectweb.asm.tree.ClassNode();
        new org.objectweb.asm.ClassReader(bytes).accept(node, 0);
        var method = node.methods.getFirst();
        method.instructions.insert(new org.objectweb.asm.tree.InsnNode(Opcodes.POP2));
        method.instructions.insert(new org.objectweb.asm.tree.FieldInsnNode(Opcodes.GETFIELD,
                "org/lwjgl/opengl/GLCapabilities", "glGenTextures", "J"));
        method.instructions.insert(new org.objectweb.asm.tree.MethodInsnNode(Opcodes.INVOKESTATIC,
                "org/lwjgl/opengl/GL", "getCapabilities", "()Lorg/lwjgl/opengl/GLCapabilities;", false));
        var writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        var result = new RuntimePreparation().prepare(writer.toByteArray());
        assertFalse(result.ready());
        assertTrue(result.blockers().stream().anyMatch(s -> s.contains("GL.getCapabilities")));
    }

    @Test void routesMethodReferenceHelpersDeclaredOnAnInterface() throws Exception {
        var writer = new ClassWriter(0);
        writer.visit(Opcodes.V25, Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT,
                "zombie/InterfaceFixture", null, "java/lang/Object", null);
        var method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "supplier", "()Ljava/util/function/IntSupplier;", null, null);
        method.visitCode();
        method.visitInvokeDynamicInsn("getAsInt", "()Ljava/util/function/IntSupplier;",
                new org.objectweb.asm.Handle(Opcodes.H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory", "metafactory",
                        "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;", false),
                org.objectweb.asm.Type.getMethodType("()I"),
                new org.objectweb.asm.Handle(Opcodes.H_INVOKESTATIC, "com/gtnewhorizons/angelica/glsm/GLStateManager", "glGetError", "()I", false),
                org.objectweb.asm.Type.getMethodType("()I"));
        method.visitInsn(Opcodes.ARETURN);
        method.visitMaxs(1, 0);
        method.visitEnd();
        writer.visitEnd();
        var result = new RuntimePreparation().prepare(writer.toByteArray());
        assertTrue(result.ready(), result.blockers().toString());
        byte[] bytes = result.requireBytecode();
        var type = new ClassLoader(getClass().getClassLoader()) {
            Class<?> define() { return defineClass(null, bytes, 0, bytes.length); }
        }.define();
        var session = RenderSession.open((owner, name, signature) -> MethodHandles.constant(int.class, 77), frames());
        try {
            var supplier = (java.util.function.IntSupplier) type.getMethod("supplier").invoke(null);
            assertEquals(77, supplier.getAsInt());
        } finally { session.close(); }
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

    static RenderSession.Frames frames() {
        return new RenderSession.Frames() {
            public void begin() { }
            public void present() { }
            public void abort() { }
            public void close() { }
        };
    }
}
