package dev.fopwoc.julia.transform.pass;

import static org.junit.jupiter.api.Assertions.*;

import dev.fopwoc.julia.runtime.ModApprovalHooks;
import dev.fopwoc.julia.transform.prepare.RuntimePreparation;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;

class ModLoadingPassTest implements Opcodes {
    @Test
    void preparesBeforeClasspathAndPatchesWithoutAnApprovalVerdict() throws Exception {
        var source = fixture();
        var prepared = new RuntimePreparation().prepare(source);
        assertTrue(prepared.ready(), prepared.blockers().toString());
        var phase = new ClassWriter(0);
        phase.visit(V25, ACC_PUBLIC, ModLoadingPass.LOADER + "$Phase", null, "java/lang/Object", null);
        var loader = new ClassLoader(getClass().getClassLoader()) {
            Class<?> define(byte[] bytes) {
                return defineClass(null, bytes, 0, bytes.length);
            }
        };
        var phaseType = loader.define(phase.toByteArray());
        var type = loader.define(prepared.requireBytecode());
        var load = type.getMethod("loadJar", Path.class, String.class, String.class, phaseType);
        var allow = new AtomicBoolean();
        var seen = new AtomicReference<ModApprovalHooks.Request>();
        ModApprovalHooks.install(request -> {
            seen.set(request);
            if (!allow.get()) throw new IllegalStateException("Preparation failed");
        });
        Path path = Path.of("approved.jar");
        var failure = assertThrows(
                java.lang.reflect.InvocationTargetException.class,
                () -> load.invoke(null, path, "example", "approved-hash", null));
        assertEquals("Preparation failed", failure.getCause().getMessage());
        assertEquals(new ModApprovalHooks.Request(path, "example", "approved-hash"), seen.get());
        assertEquals(0, type.getField("published").getInt(null));
        assertEquals(0, type.getField("patched").getInt(null));
        allow.set(true);
        assertEquals(true, load.invoke(null, path, "example", "approved-hash", null));
        assertEquals(1, type.getField("published").getInt(null));
        assertEquals(1, type.getField("patched").getInt(null));
    }

    @Test
    void refusesDriftAndRepeatedInstrumentation() {
        var node = new ClassNode();
        new ClassReader(fixture()).accept(node, 0);
        ModLoadingPass.adapt(node);
        assertThrows(IllegalArgumentException.class, () -> ModLoadingPass.adapt(node));
        node.methods.removeIf(m -> m.name.equals("loadJar"));
        assertThrows(IllegalArgumentException.class, () -> ModLoadingPass.adapt(node));
    }

    private static byte[] fixture() {
        var writer = new ClassWriter(0);
        writer.visit(V25, ACC_PUBLIC, ModLoadingPass.LOADER, null, "java/lang/Object", null);
        writer.visitField(ACC_PUBLIC | ACC_STATIC, "published", "I", null, null);
        writer.visitField(ACC_PUBLIC | ACC_STATIC, "patched", "I", null, null);
        var publish = writer.visitMethod(
                ACC_PRIVATE | ACC_STATIC,
                "addJarToClasspath",
                "(Ljava/nio/file/Path;Ljava/lang/String;Ljava/lang/String;)Z",
                null,
                null);
        publish.visitCode();
        publish.visitFieldInsn(GETSTATIC, ModLoadingPass.LOADER, "published", "I");
        publish.visitInsn(ICONST_1);
        publish.visitInsn(IADD);
        publish.visitFieldInsn(PUTSTATIC, ModLoadingPass.LOADER, "published", "I");
        publish.visitInsn(ICONST_1);
        publish.visitInsn(IRETURN);
        publish.visitMaxs(2, 3);
        publish.visitEnd();
        var load = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "loadJar",
                "(Ljava/nio/file/Path;Ljava/lang/String;Ljava/lang/String;L" + ModLoadingPass.LOADER + "$Phase;)Z",
                null,
                null);
        load.visitCode();
        load.visitVarInsn(ALOAD, 0);
        load.visitVarInsn(ALOAD, 1);
        load.visitVarInsn(ALOAD, 2);
        load.visitMethodInsn(
                INVOKESTATIC,
                ModLoadingPass.LOADER,
                "addJarToClasspath",
                "(Ljava/nio/file/Path;Ljava/lang/String;Ljava/lang/String;)Z",
                false);
        load.visitInsn(POP);
        load.visitFieldInsn(GETSTATIC, ModLoadingPass.LOADER, "patched", "I");
        load.visitInsn(ICONST_1);
        load.visitInsn(IADD);
        load.visitFieldInsn(PUTSTATIC, ModLoadingPass.LOADER, "patched", "I");
        load.visitInsn(ICONST_1);
        load.visitInsn(IRETURN);
        load.visitMaxs(3, 4);
        load.visitEnd();
        return writer.toByteArray();
    }
}
