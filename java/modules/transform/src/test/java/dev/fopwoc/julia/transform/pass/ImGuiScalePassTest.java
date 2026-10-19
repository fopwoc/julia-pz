package dev.fopwoc.julia.transform.pass;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;

class ImGuiScalePassTest implements Opcodes {
    @Test
    void drawsRetinaDataAtLogicalSizeWithoutChangingSourceScale() throws Exception {
        var vector = type("imgui/ImVec2");
        vector.visitField(ACC_PUBLIC, "x", "F", null, null);
        vector.visitField(ACC_PUBLIC, "y", "F", null, null);
        var data = type("imgui/ImDrawData");
        var query = data.visitMethod(ACC_PUBLIC, "getFramebufferScale", "(Limgui/ImVec2;)V", null, null);
        query.visitCode();
        for (String axis : new String[] {"x", "y"}) {
            query.visitVarInsn(ALOAD, 1);
            query.visitInsn(FCONST_2);
            query.visitFieldInsn(PUTFIELD, "imgui/ImVec2", axis, "F");
        }
        query.visitInsn(RETURN);
        query.visitMaxs(2, 2);
        query.visitEnd();
        var renderer = type(ImGuiPass.RENDERER);
        renderer.visitField(ACC_PUBLIC | ACC_STATIC, "width", "F", null, null);
        var render = renderer.visitMethod(
                ACC_PUBLIC | ACC_STATIC, "renderDrawData", "(Limgui/ImDrawData;Limgui/ImVec2;)V", null, null);
        render.visitCode();
        render.visitVarInsn(ALOAD, 0);
        render.visitVarInsn(ALOAD, 1);
        render.visitMethodInsn(INVOKEVIRTUAL, "imgui/ImDrawData", "getFramebufferScale", "(Limgui/ImVec2;)V", false);
        render.visitVarInsn(ALOAD, 1);
        render.visitFieldInsn(GETFIELD, "imgui/ImVec2", "x", "F");
        render.visitLdcInsn(100.0f);
        render.visitInsn(FMUL);
        render.visitFieldInsn(PUTSTATIC, ImGuiPass.RENDERER, "width", "F");
        render.visitInsn(RETURN);
        render.visitMaxs(2, 2);
        render.visitEnd();
        var node = new ClassNode();
        new ClassReader(renderer.toByteArray()).accept(node, 0);
        ImGuiScalePass.adapt(node);
        assertThrows(IllegalArgumentException.class, () -> ImGuiScalePass.adapt(node));
        var writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        var loader = new ClassLoader(getClass().getClassLoader()) {
            Class<?> define(byte[] bytes) {
                return defineClass(null, bytes, 0, bytes.length);
            }
        };
        var vecType = loader.define(vector.toByteArray());
        var dataType = loader.define(data.toByteArray());
        var rendererType = loader.define(writer.toByteArray());
        var vec = vecType.getConstructor().newInstance();
        var drawData = dataType.getConstructor().newInstance();
        var draw = rendererType.getMethod("renderDrawData", dataType, vecType);
        draw.invoke(null, drawData, vec);
        assertEquals(100.0f, rendererType.getField("width").getFloat(null));
        assertEquals(1.0f, vecType.getField("y").getFloat(vec));
        dataType.getMethod("getFramebufferScale", vecType).invoke(drawData, vec);
        assertEquals(2.0f, vecType.getField("x").getFloat(vec));
        assertEquals(2.0f, vecType.getField("y").getFloat(vec));
    }

    @Test
    void rejectsMissingScaleAnchors() {
        var node = new ClassNode();
        node.name = ImGuiPass.RENDERER;
        assertThrows(IllegalArgumentException.class, () -> ImGuiScalePass.adapt(node));
    }

    private static ClassWriter type(String name) {
        var type = new ClassWriter(0);
        type.visit(V25, ACC_PUBLIC, name, null, "java/lang/Object", null);
        var init = type.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(RETURN);
        init.visitMaxs(1, 1);
        init.visitEnd();
        return type;
    }
}
