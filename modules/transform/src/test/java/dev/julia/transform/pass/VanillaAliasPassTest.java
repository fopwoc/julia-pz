package dev.julia.transform.pass;

import dev.julia.transform.Endpoint;
import dev.julia.transform.TestFrames;
import dev.julia.transform.prepare.RuntimePreparation;
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
        var session = RenderSession.open((owner, name, signature) -> target, TestFrames.idle());
        try { type.getMethod("depth", float.class).invoke(null, 0.1f); }
        finally { session.close(); }
        assertEquals(java.util.List.of((double) 0.1f), DEPTHS);
    }

    @Test void bypassesTheInheritedEmptyUniformReadImplementation() throws Exception {
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
        assertTrue(prepared.ready(), prepared.blockers().toString());
        var node = new org.objectweb.asm.tree.ClassNode();
        new org.objectweb.asm.ClassReader(prepared.requireBytecode()).accept(node, 0);
        assertTrue(node.methods.stream().flatMap(m -> java.util.Arrays.stream(m.instructions.toArray()))
                .filter(org.objectweb.asm.tree.InvokeDynamicInsnNode.class::isInstance)
                .map(org.objectweb.asm.tree.InvokeDynamicInsnNode.class::cast)
                .anyMatch(call -> java.util.Arrays.asList(call.bsmArgs).contains(Endpoint.UNIFORM_QUERY_BRIDGE.owner())));
    }

    static void depth(double value) { DEPTHS.add(value); }
}
