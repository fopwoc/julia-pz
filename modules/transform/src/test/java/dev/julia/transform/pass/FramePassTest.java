package dev.julia.transform.pass;

import dev.julia.runtime.RenderSession;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import static org.junit.jupiter.api.Assertions.*;

class FramePassTest {
    @Test void bracketsRealBytecodeAndRetainsHostUpdate() throws Exception {
        var type = load(fixture(true));
        var events = new ArrayList<String>();
        var session = session(events);
        zombie.core.SpriteRenderer.fail = false;
        int before = org.lwjglx.opengl.Display.updates;
        try { assertEquals(true, type.getMethod("lockStepRenderStep").invoke(null)); }
        finally { session.close(); }
        assertEquals(java.util.List.of("begin", "present", "close"), events);
        assertEquals(before + 1, org.lwjglx.opengl.Display.updates);
    }

    @Test void abortsFailedDrawWithoutPresentingOrSwallowingFailure() throws Exception {
        var type = load(fixture(true));
        var events = new ArrayList<String>();
        var session = session(events);
        zombie.core.SpriteRenderer.fail = true;
        int before = org.lwjglx.opengl.Display.updates;
        try {
            var failure = assertThrows(InvocationTargetException.class, () -> type.getMethod("lockStepRenderStep").invoke(null));
            assertEquals("synthetic draw failure", failure.getCause().getMessage());
        } finally { session.close(); zombie.core.SpriteRenderer.fail = false; }
        assertEquals(java.util.List.of("begin", "abort", "close"), events);
        assertEquals(before, org.lwjglx.opengl.Display.updates);
    }

    @Test void rejectsMissingAndAlreadyTransformedAnchors() {
        var missing = new ClassNode();
        new ClassReader(fixture(false)).accept(missing, 0);
        assertThrows(IllegalArgumentException.class, () -> FramePass.adapt(missing));
        var valid = new ClassNode();
        new ClassReader(fixture(true)).accept(valid, 0);
        FramePass.adapt(valid);
        assertThrows(IllegalArgumentException.class, () -> FramePass.adapt(valid));
    }

    private static Class<?> load(byte[] source) {
        var node = new ClassNode();
        new ClassReader(source).accept(node, 0);
        assertTrue(FramePass.adapt(node));
        var writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        byte[] bytes = writer.toByteArray();
        return new ClassLoader(FramePassTest.class.getClassLoader()) {
            Class<?> define() { return defineClass(null, bytes, 0, bytes.length); }
        }.define();
    }

    private static byte[] fixture(boolean swap) {
        var writer = new ClassWriter(0);
        writer.visit(Opcodes.V25, Opcodes.ACC_PUBLIC, FramePass.RENDER_THREAD, null, "java/lang/Object", null);
        var method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "lockStepRenderStep", "()Z", null, null);
        method.visitCode();
        method.visitFieldInsn(Opcodes.GETSTATIC, "zombie/core/SpriteRenderer", "instance", "Lzombie/core/SpriteRenderer;");
        method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "zombie/core/SpriteRenderer", "postRender", "()V", false);
        if (swap) {
            method.visitInsn(Opcodes.ICONST_1);
            method.visitMethodInsn(Opcodes.INVOKESTATIC, "org/lwjglx/opengl/Display", "update", "(Z)V", false);
        }
        method.visitInsn(Opcodes.ICONST_1);
        method.visitInsn(Opcodes.IRETURN);
        method.visitMaxs(1, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static RenderSession session(ArrayList<String> events) {
        return RenderSession.open((owner, name, type) -> { throw new NoSuchMethodException(name); }, new RenderSession.Frames() {
            public void begin() { events.add("begin"); }
            public void present() { events.add("present"); }
            public void abort() { events.add("abort"); }
            public void close() { events.add("close"); }
        });
    }
}
