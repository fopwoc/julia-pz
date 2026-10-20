package dev.fopwoc.julia.transform.pass;

import static org.junit.jupiter.api.Assertions.*;

import dev.fopwoc.julia.runtime.RenderSession;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.lwjglx.opengl.Display;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import zombie.core.SpriteRenderer;

class ApprovalPassTest implements Opcodes {
    @Test
    void submitsEachApprovalFrameAndAbortsFailuresBeforeHostUpdate() throws Exception {
        var node = frontend(true);
        ApprovalPass.adapt(node);
        var writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        var owned = context();
        var dialog = type(ApprovalPass.DIALOG);
        var loader = new ClassLoader(getClass().getClassLoader()) {
            Class<?> define(byte[] bytes) {
                return defineClass(null, bytes, 0, bytes.length);
            }
        };
        var ownedType = loader.define(owned.toByteArray());
        var dialogType = loader.define(dialog.toByteArray());
        var caller = loader.define(writer.toByteArray());
        var pump = caller.getMethod("pump", ownedType, dialogType);
        var context = ownedType.getConstructor().newInstance();
        var data = dialogType.getConstructor().newInstance();
        var events = new ArrayList<String>();
        int updates = Display.updates;
        SpriteRenderer.fail = false;
        try (var session = session(events)) {
            assertNotNull(session);
            pump.invoke(null, context, data);
            pump.invoke(null, context, data);
            assertEquals(List.of("begin", "present", "begin", "present"), events);
            assertEquals(updates + 2, Display.updates);
            SpriteRenderer.fail = true;
            var failure = assertThrows(InvocationTargetException.class, () -> pump.invoke(null, context, data));
            assertEquals("synthetic draw failure", failure.getCause().getMessage());
            assertEquals(updates + 2, Display.updates);
        } finally {
            SpriteRenderer.fail = false;
        }
        assertEquals(List.of("begin", "present", "begin", "present", "begin", "abort", "close"), events);
    }

    @Test
    void rejectsIncompleteAndRepeatedFramesAndUsesLogicalApprovalSize() {
        assertThrows(IllegalArgumentException.class, () -> ApprovalPass.adapt(frontend(false)));
        var valid = frontend(true);
        ApprovalPass.adapt(valid);
        assertThrows(IllegalArgumentException.class, () -> ApprovalPass.adapt(valid));
        var owned = context();
        for (String helper : List.of("size", "fontSize")) {
            var size = owned.visitMethod(ACC_PUBLIC | ACC_STATIC, helper, "(J[I[I)V", null, null);
            size.visitCode();
            size.visitVarInsn(LLOAD, 0);
            size.visitVarInsn(ALOAD, 2);
            size.visitVarInsn(ALOAD, 3);
            size.visitMethodInsn(INVOKESTATIC, "org/lwjgl/glfw/GLFW", "glfwGetFramebufferSize", "(J[I[I)V", false);
            size.visitInsn(RETURN);
            size.visitMaxs(4, 4);
            size.visitEnd();
        }
        var node = new ClassNode();
        new ClassReader(owned.toByteArray()).accept(node, 0);
        ApprovalPass.adapt(node);
        var query = node.methods.stream()
                .filter(m -> m.name.equals("size"))
                .findFirst()
                .orElseThrow();
        var calls = new ArrayList<String>();
        for (var instruction : query.instructions) if (instruction instanceof MethodInsnNode call) calls.add(call.name);
        assertEquals(List.of("glfwGetWindowSize"), calls);
        assertThrows(IllegalArgumentException.class, () -> ApprovalPass.adapt(node));
    }

    @Test
    void drawsJuliaStatusOverTheApprovalDialogBeforeImGuiRenders() {
        var node = imguiContext(true);
        ApprovalPass.adapt(node);
        var render = node.methods.stream()
                .filter(m -> m.name.equals("render"))
                .findFirst()
                .orElseThrow();
        var calls = new ArrayList<String>();
        for (var instruction : render.instructions)
            if (instruction instanceof MethodInsnNode call) calls.add(call.owner + "." + call.name);
        assertEquals(
                List.of(
                        ApprovalPass.DIALOG + ".draw",
                        "imgui/ImGui.getForegroundDrawList",
                        "imgui/ImGui.getIO",
                        "imgui/ImGuiIO.getDisplaySizeY",
                        "imgui/ImGui.getTextLineHeight",
                        "dev/fopwoc/julia/runtime/JuliaStatus.label",
                        "imgui/ImDrawList.addText",
                        "imgui/ImGui.render"),
                calls);
    }

    @Test
    void leavesAnUnrecognizedApprovalFrameWithoutAStatusLine() {
        var node = imguiContext(false);
        ApprovalPass.adapt(node);
        for (var method : node.methods)
            for (var instruction : method.instructions)
                assertFalse(instruction instanceof MethodInsnNode call && call.name.equals("addText"));
    }

    /** ZombieBuddy's owned ImGui frame: optional dialog draw, ImGui render, and the two framebuffer-size queries. */
    private static ClassNode imguiContext(boolean dialogFirst) {
        var writer = type(ApprovalPass.CONTEXT);
        var render = writer.visitMethod(ACC_PUBLIC, "render", "(L" + ApprovalPass.DIALOG + ";)V", null, null);
        render.visitCode();
        if (dialogFirst) {
            render.visitVarInsn(ALOAD, 1);
            render.visitMethodInsn(INVOKEVIRTUAL, ApprovalPass.DIALOG, "draw", "()V", false);
        }
        render.visitMethodInsn(INVOKESTATIC, "imgui/ImGui", "render", "()V", false);
        render.visitInsn(RETURN);
        render.visitMaxs(1, 2);
        render.visitEnd();
        for (String helper : List.of("size", "fontSize")) {
            var size = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, helper, "(J[I[I)V", null, null);
            size.visitCode();
            size.visitVarInsn(LLOAD, 0);
            size.visitVarInsn(ALOAD, 2);
            size.visitVarInsn(ALOAD, 3);
            size.visitMethodInsn(INVOKESTATIC, "org/lwjgl/glfw/GLFW", "glfwGetFramebufferSize", "(J[I[I)V", false);
            size.visitInsn(RETURN);
            size.visitMaxs(4, 4);
            size.visitEnd();
        }
        var node = new ClassNode();
        new ClassReader(writer.toByteArray()).accept(node, 0);
        return node;
    }

    private static ClassNode frontend(boolean update) {
        var writer = type(ApprovalPass.FRONTEND);
        var pump = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "pump",
                "(L" + ApprovalPass.CONTEXT + ";L" + ApprovalPass.DIALOG + ";)V",
                null,
                null);
        pump.visitCode();
        pump.visitVarInsn(ALOAD, 0);
        pump.visitVarInsn(ALOAD, 1);
        pump.visitMethodInsn(INVOKEVIRTUAL, ApprovalPass.CONTEXT, "render", "(L" + ApprovalPass.DIALOG + ";)V", false);
        if (update) {
            pump.visitInsn(ICONST_0);
            pump.visitMethodInsn(INVOKESTATIC, "org/lwjglx/opengl/Display", "update", "(Z)V", false);
        }
        pump.visitInsn(RETURN);
        pump.visitMaxs(2, 2);
        pump.visitEnd();
        var node = new ClassNode();
        new ClassReader(writer.toByteArray()).accept(node, 0);
        return node;
    }

    private static ClassWriter context() {
        var writer = type(ApprovalPass.CONTEXT);
        var render = writer.visitMethod(ACC_PUBLIC, "render", "(L" + ApprovalPass.DIALOG + ";)V", null, null);
        render.visitCode();
        render.visitFieldInsn(GETSTATIC, "zombie/core/SpriteRenderer", "instance", "Lzombie/core/SpriteRenderer;");
        render.visitMethodInsn(INVOKEVIRTUAL, "zombie/core/SpriteRenderer", "postRender", "()V", false);
        render.visitInsn(RETURN);
        render.visitMaxs(1, 2);
        render.visitEnd();
        return writer;
    }

    private static ClassWriter type(String name) {
        var writer = new ClassWriter(0);
        writer.visit(V25, ACC_PUBLIC, name, null, "java/lang/Object", null);
        var constructor = writer.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(ALOAD, 0);
        constructor.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(RETURN);
        constructor.visitMaxs(1, 1);
        constructor.visitEnd();
        return writer;
    }

    private static RenderSession session(List<String> events) {
        return RenderSession.open(
                (owner, name, type) -> {
                    throw new NoSuchMethodException(name);
                },
                new RenderSession.Frames() {
                    public void begin() {
                        events.add("begin");
                    }

                    public void present() {
                        events.add("present");
                    }

                    public void abort() {
                        events.add("abort");
                    }

                    public void close() {
                        events.add("close");
                    }
                });
    }
}
