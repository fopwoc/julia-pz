package dev.fopwoc.julia.transform.pass;

import static org.junit.jupiter.api.Assertions.*;

import dev.fopwoc.julia.transform.prepare.RuntimePreparation;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

class ImGuiPassTest implements Opcodes {
    @Test
    void preservesDisabledGuardAndPassesSameOwnedDataToRendererThenFree() throws Exception {
        var prepared = new RuntimePreparation().prepare(caller("(Limgui/ImDrawData;)V"));
        assertTrue(prepared.ready(), prepared.blockers().toString());
        assertEquals(Set.of(ImGuiPass.RENDERER), prepared.requiredConsumers());
        Map<String, byte[]> classes = fixtures();
        classes.put("test.ImGuiCaller", prepared.requireBytecode());
        var loader = new ClassLoader(getClass().getClassLoader()) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (!classes.containsKey(name)) return super.loadClass(name, resolve);
                synchronized (getClassLoadingLock(name)) {
                    var type = findLoadedClass(name);
                    if (type == null) type = findClass(name);
                    if (resolve) resolveClass(type);
                    return type;
                }
            }

            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                byte[] bytes = classes.get(name);
                if (bytes == null) throw new ClassNotFoundException(name);
                return defineClass(name, bytes, 0, bytes.length);
            }
        };
        var dataType = loader.loadClass("imgui.ImDrawData");
        var data = dataType.getConstructor().newInstance();
        var core = loader.loadClass("zombie.core.Core");
        var renderer = loader.loadClass("imgui.gl3.ImGuiImplGl3");
        var imgui = loader.loadClass("imgui.ImGui");
        var draw = loader.loadClass("test.ImGuiCaller").getMethod("draw", dataType);
        draw.invoke(null, data);
        assertNull(renderer.getField("last").get(null));
        assertNull(imgui.getField("last").get(null));
        core.getField("enabled").setBoolean(null, true);
        draw.invoke(null, data);
        assertSame(data, renderer.getField("last").get(null));
        assertSame(data, imgui.getField("last").get(null));
        assertSame(data, imgui.getField("renderedBeforeFree").get(null));
    }

    @Test
    void leavesUnknownDisplayOverloadsBlocked() throws Exception {
        var prepared = new RuntimePreparation().prepare(caller("(Ljava/lang/Object;)V"));
        assertFalse(prepared.ready());
        assertTrue(prepared.blockers().stream().anyMatch(blocker -> blocker.startsWith("HOST_RENDERER ")));
    }

    private static byte[] caller(String descriptor) {
        var type = type("test/ImGuiCaller");
        var draw = type.visitMethod(ACC_PUBLIC | ACC_STATIC, "draw", descriptor, null, null);
        draw.visitCode();
        draw.visitVarInsn(ALOAD, 0);
        draw.visitMethodInsn(INVOKESTATIC, "org/lwjglx/opengl/Display", "drawImGuiDrawData", descriptor, false);
        draw.visitInsn(RETURN);
        draw.visitMaxs(1, 1);
        draw.visitEnd();
        return type.toByteArray();
    }

    private static Map<String, byte[]> fixtures() {
        Map<String, byte[]> result = new HashMap<>();
        result.put("imgui.ImDrawData", type("imgui/ImDrawData").toByteArray());
        var core = type("zombie/core/Core");
        core.visitField(ACC_PUBLIC | ACC_STATIC, "enabled", "Z", null, null);
        var enabled = core.visitMethod(ACC_PUBLIC | ACC_STATIC, "isImGui", "()Z", null, null);
        enabled.visitCode();
        enabled.visitFieldInsn(GETSTATIC, "zombie/core/Core", "enabled", "Z");
        enabled.visitInsn(IRETURN);
        enabled.visitMaxs(1, 0);
        enabled.visitEnd();
        result.put("zombie.core.Core", core.toByteArray());
        var renderer = type(ImGuiPass.RENDERER);
        renderer.visitField(ACC_PUBLIC | ACC_STATIC, "last", "Ljava/lang/Object;", null, null);
        var render = renderer.visitMethod(ACC_PUBLIC, "renderDrawData", "(Limgui/ImDrawData;)V", null, null);
        render.visitCode();
        render.visitVarInsn(ALOAD, 1);
        render.visitFieldInsn(PUTSTATIC, ImGuiPass.RENDERER, "last", "Ljava/lang/Object;");
        finish(render, 2);
        result.put("imgui.gl3.ImGuiImplGl3", renderer.toByteArray());
        var imgui = type("imgui/ImGui");
        imgui.visitField(ACC_PUBLIC | ACC_STATIC, "last", "Ljava/lang/Object;", null, null);
        imgui.visitField(ACC_PUBLIC | ACC_STATIC, "renderedBeforeFree", "Ljava/lang/Object;", null, null);
        var free = imgui.visitMethod(ACC_PUBLIC | ACC_STATIC, "freeDrawData", "(Limgui/ImDrawData;)V", null, null);
        free.visitCode();
        free.visitVarInsn(ALOAD, 0);
        free.visitFieldInsn(PUTSTATIC, "imgui/ImGui", "last", "Ljava/lang/Object;");
        free.visitFieldInsn(GETSTATIC, ImGuiPass.RENDERER, "last", "Ljava/lang/Object;");
        free.visitFieldInsn(PUTSTATIC, "imgui/ImGui", "renderedBeforeFree", "Ljava/lang/Object;");
        finish(free, 1);
        result.put("imgui.ImGui", imgui.toByteArray());
        var display = type("org/lwjglx/opengl/Display");
        display.visitField(ACC_PUBLIC | ACC_STATIC, "imGuiGl3", "L" + ImGuiPass.RENDERER + ";", null, null);
        var init = display.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null);
        init.visitCode();
        init.visitTypeInsn(NEW, ImGuiPass.RENDERER);
        init.visitInsn(DUP);
        init.visitMethodInsn(INVOKESPECIAL, ImGuiPass.RENDERER, "<init>", "()V", false);
        init.visitFieldInsn(PUTSTATIC, "org/lwjglx/opengl/Display", "imGuiGl3", "L" + ImGuiPass.RENDERER + ";");
        init.visitInsn(RETURN);
        init.visitMaxs(2, 0);
        init.visitEnd();
        // Deliberately no drawImGuiDrawData method: successful linkage proves the opaque call is gone.
        result.put("org.lwjglx.opengl.Display", display.toByteArray());
        return result;
    }

    private static ClassWriter type(String name) {
        var type = new ClassWriter(0);
        type.visit(V25, ACC_PUBLIC, name, null, "java/lang/Object", null);
        var constructor = type.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(ALOAD, 0);
        constructor.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(RETURN);
        constructor.visitMaxs(1, 1);
        constructor.visitEnd();
        return type;
    }

    private static void finish(MethodVisitor method, int locals) {
        method.visitInsn(RETURN);
        method.visitMaxs(1, locals);
        method.visitEnd();
    }
}
