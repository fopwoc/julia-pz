package dev.julia.probe.host;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import static org.junit.jupiter.api.Assertions.*;

class WindowBindingsTest {
    private static final String DEVICE = "com.gtnewhorizons.angelica.sdlgpu.device.Device";

    @Test
    void adaptsDisplayQueriesEvenForLwjgl3AwareDeviceClasses() {
        byte[] transformed = WindowBindings.adapt(DEVICE, fixture());
        var node = new ClassNode();
        new ClassReader(transformed).accept(node, 0);
        var method = node.methods.getFirst();
        var call = (MethodInsnNode) method.instructions.getFirst();
        assertEquals("dev/julia/probe/window/HostWindow", call.owner);
        assertEquals("getWindow", call.name);
        assertEquals("()J", call.desc);
        var input = (MethodInsnNode) method.instructions.get(2);
        assertEquals("org/lwjgl/glfw/GLFW", input.owner);
        assertEquals("glfwPollEvents", input.name);
        var swap = (MethodInsnNode) method.instructions.get(3);
        assertEquals("org/lwjglx/opengl/Display", swap.owner);
        assertEquals("swapBuffers", swap.name);
    }

    @Test
    void excludesGameAndBridgeDisplayImplementations() {
        byte[] original = fixture();
        for (String name : new String[]{"zombie.core.opengl.RenderThread", "org.lwjglx.opengl.Display",
                "com.gtnewhorizons.angelica.sdlgpu.SDLGPUGate"}) {
            assertSame(original, WindowBindings.adapt(name, original));
        }
    }

    private static byte[] fixture() {
        var writer = new ClassWriter(0);
        writer.visit(Opcodes.V25, Opcodes.ACC_PUBLIC, DEVICE.replace('.', '/'), null, "java/lang/Object", null);
        writer.visitAnnotation("Lme/eigenraven/lwjgl3ify/api/Lwjgl3Aware;", false).visitEnd();
        var method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "example", "()V", null, null);
        method.visitCode();
        method.visitMethodInsn(Opcodes.INVOKESTATIC, "org/lwjglx/opengl/Display", "getWindow", "()J", false);
        method.visitInsn(Opcodes.POP2);
        method.visitMethodInsn(Opcodes.INVOKESTATIC, "org/lwjgl/glfw/GLFW", "glfwPollEvents", "()V", false);
        method.visitMethodInsn(Opcodes.INVOKESTATIC, "org/lwjglx/opengl/Display", "swapBuffers", "()V", false);
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(2, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }
}
