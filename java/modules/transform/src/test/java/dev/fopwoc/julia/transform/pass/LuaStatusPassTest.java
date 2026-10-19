package dev.fopwoc.julia.transform.pass;

import static org.junit.jupiter.api.Assertions.*;

import dev.fopwoc.julia.runtime.JuliaStatus;
import dev.fopwoc.julia.transform.prepare.ArchivePreparation;
import dev.fopwoc.julia.transform.prepare.RuntimePreparation;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

class LuaStatusPassTest implements Opcodes {
    @Test
    void exposesLiveReadOnlyStatusBeforeNormalLuaExposureWithoutZombieBuddy() throws Exception {
        var writer = new ClassWriter(0);
        String owner = ArchivePreparation.LUA_EXPOSER;
        writer.visit(V25, ACC_PUBLIC, owner, null, "java/lang/Object", null);
        writer.visitField(ACC_PUBLIC, "seen", "Ljava/lang/Class;", null, null);
        var init = writer.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(RETURN);
        init.visitMaxs(1, 1);
        init.visitEnd();
        var setter = writer.visitMethod(ACC_PUBLIC, "setExposed", "(Ljava/lang/Class;)V", null, null);
        setter.visitCode();
        setter.visitVarInsn(ALOAD, 0);
        setter.visitVarInsn(ALOAD, 1);
        setter.visitFieldInsn(PUTFIELD, owner, "seen", "Ljava/lang/Class;");
        setter.visitInsn(RETURN);
        setter.visitMaxs(2, 2);
        setter.visitEnd();
        var expose = writer.visitMethod(ACC_PUBLIC, "exposeAll", "()V", null, null);
        expose.visitCode();
        expose.visitInsn(RETURN);
        expose.visitMaxs(0, 1);
        expose.visitEnd();
        var prepared = new RuntimePreparation().prepare(writer.toByteArray());
        assertTrue(prepared.ready(), prepared.blockers().toString());
        byte[] bytes = prepared.requireBytecode();
        var type = new ClassLoader(getClass().getClassLoader()) {
            Class<?> define() {
                return defineClass(null, bytes, 0, bytes.length);
            }
        }.define();
        var instance = type.getConstructor().newInstance();
        type.getMethod("exposeAll").invoke(instance);
        assertSame(JuliaStatus.class, type.getField("seen").get(instance));
        String previous = System.getProperty("julia.renderer.backend");
        try {
            System.clearProperty("julia.renderer.backend");
            assertFalse(JuliaStatus.isActive(), "Loading the agent alone must not claim an active renderer");
            System.setProperty("julia.renderer.backend", "metal");
            assertTrue(JuliaStatus.isActive());
            assertEquals("metal", JuliaStatus.getBackend());
        } finally {
            if (previous == null) System.clearProperty("julia.renderer.backend");
            else System.setProperty("julia.renderer.backend", previous);
        }
    }
}
