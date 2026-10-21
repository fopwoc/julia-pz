package dev.fopwoc.julia.transform.pass;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

class SwapBuffersPassTest implements Opcodes {
    @Test
    void swapsBecomeVerifiableNoOpsThatKeepTheRestOfTheMethod() throws Exception {
        var node = new ClassNode();
        new ClassReader(fixture()).accept(node, 0);
        SwapBuffersPass.adapt(node);
        for (var method : node.methods)
            for (var instruction : method.instructions)
                assertFalse(instruction instanceof MethodInsnNode call && call.owner.equals("org/lwjgl/glfw/GLFW"));

        var writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        byte[] bytes = writer.toByteArray();
        // A fresh loader, so the verifier checks the rewritten stack and no GLFW class is needed to run it.
        var type = new ClassLoader(null) {
            Class<?> define() {
                return defineClass(null, bytes, 0, bytes.length);
            }
        }.define();
        assertEquals(7L, type.getMethod("swapBuffers", long.class).invoke(null, 7L));
    }

    @Test
    void failsWhenTheSwapAnchorMoved() {
        var node = new ClassNode();
        node.name = SwapBuffersPass.DISPLAY;
        assertThrows(IllegalArgumentException.class, () -> SwapBuffersPass.adapt(node));
    }

    private static byte[] fixture() {
        var writer = new ClassWriter(0);
        writer.visit(V25, ACC_PUBLIC, SwapBuffersPass.DISPLAY, null, "java/lang/Object", null);
        var swap = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "swapBuffers", "(J)J", null, null);
        swap.visitCode();
        swap.visitVarInsn(LLOAD, 0);
        swap.visitMethodInsn(INVOKESTATIC, "org/lwjgl/glfw/GLFW", "glfwSwapBuffers", "(J)V", false);
        swap.visitVarInsn(LLOAD, 0);
        swap.visitInsn(LRETURN);
        swap.visitMaxs(2, 2);
        swap.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }
}
