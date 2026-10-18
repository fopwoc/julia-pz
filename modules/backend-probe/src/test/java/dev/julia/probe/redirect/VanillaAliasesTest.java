package dev.julia.probe.redirect;

import dev.julia.runtime.RenderSession;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import static org.junit.jupiter.api.Assertions.*;

class VanillaAliasesTest {
    private static final ArrayList<Double> DEPTHS = new ArrayList<>();

    @Test void widensFloatOnTheOperandStackBeforeTypedDispatch() throws Exception {
        var writer = new ClassWriter(0);
        writer.visit(Opcodes.V25, Opcodes.ACC_PUBLIC, "zombie/AliasFixture", null, "java/lang/Object", null);
        var method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "depth", "(F)V", null, null);
        method.visitCode();
        method.visitVarInsn(Opcodes.FLOAD, 0);
        method.visitMethodInsn(Opcodes.INVOKESTATIC, "org/lwjgl/opengl/GL43", "glClearDepthf", "(F)V", false);
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(1, 1);
        method.visitEnd();
        writer.visitEnd();
        var prepared = new RuntimePreparation().prepare(writer.toByteArray());
        assertTrue(prepared.ready(), prepared.blockers().toString());
        byte[] bytes = prepared.requireBytecode();
        var type = new ClassLoader(getClass().getClassLoader()) {
            Class<?> define() { return defineClass(null, bytes, 0, bytes.length); }
        }.define();
        DEPTHS.clear();
        var target = MethodHandles.lookup().findStatic(VanillaAliasesTest.class, "depth", MethodType.methodType(void.class, double.class));
        var session = RenderSession.open((owner, name, signature) -> target, RuntimePreparationTest.frames());
        try { type.getMethod("depth", float.class).invoke(null, 0.1f); }
        finally { session.close(); }
        assertEquals(java.util.List.of((double) 0.1f), DEPTHS);
    }

    @Test void refusesTheInheritedEmptyUniformReadImplementation() throws Exception {
        var writer = new ClassWriter(0);
        writer.visit(Opcodes.V25, Opcodes.ACC_PUBLIC, "zombie/UniformReadFixture", null, "java/lang/Object", null);
        var method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "read", "(IILjava/nio/FloatBuffer;)V", null, null);
        method.visitCode();
        method.visitVarInsn(Opcodes.ILOAD, 0);
        method.visitVarInsn(Opcodes.ILOAD, 1);
        method.visitVarInsn(Opcodes.ALOAD, 2);
        method.visitMethodInsn(Opcodes.INVOKESTATIC, "com/gtnewhorizons/angelica/glsm/GLStateManager", "glGetUniform", "(IILjava/nio/FloatBuffer;)V", false);
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(3, 3);
        method.visitEnd();
        writer.visitEnd();
        var prepared = new RuntimePreparation().prepare(writer.toByteArray());
        assertFalse(prepared.ready());
        assertTrue(prepared.blockers().stream().anyMatch(s -> s.startsWith("UNIMPLEMENTED_BACKEND")), prepared.blockers().toString());
        assertThrows(IllegalStateException.class, prepared::requireBytecode);
    }

    static void depth(double value) { DEPTHS.add(value); }
}
