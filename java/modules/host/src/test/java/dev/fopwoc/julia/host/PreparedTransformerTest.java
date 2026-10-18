package dev.fopwoc.julia.host;

import static org.junit.jupiter.api.Assertions.*;

import dev.fopwoc.julia.transform.prepare.ArchivePreparation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

class PreparedTransformerTest implements Opcodes {
    @TempDir
    Path directory;

    @Test
    void installsOnlyUnchangedPreparedBytesAndRefusesLateGraphicsConsumers() throws Exception {
        byte[] draw = draw("zombie/LiveFixture", "glGetError");
        Path archive = directory.resolve("fixture.jar");
        try (var jar = new JarOutputStream(Files.newOutputStream(archive))) {
            jar.putNextEntry(new JarEntry("zombie/LiveFixture.class"));
            jar.write(draw);
            jar.closeEntry();
            jar.putNextEntry(new JarEntry("zombie/core/opengl/RenderThread.class"));
            jar.write(frame());
            jar.closeEntry();
        }
        var plan = ArchivePreparation.inspect(List.of(archive));
        assertTrue(plan.ready(), plan.blockers().toString());
        var owner = getClass().getClassLoader();
        var transform = new PreparedTransformer(plan, owner);
        byte[] prepared = transform.transform(owner, "zombie/LiveFixture", null, null, draw);
        assertNotNull(prepared);
        assertDoesNotThrow(() -> new ClassReader(prepared));
        assertFalse(Arrays.equals(draw, prepared));
        assertEquals(
                0,
                transform.transform(
                                owner, "zombie/LiveFixture", null, null, draw("zombie/LiveFixture", "glUnknownChanged"))
                        .length);
        assertEquals(
                0,
                transform.transform(owner, "zombie/Unplanned", null, null, draw("zombie/Unplanned", "glGetError"))
                        .length);
        assertEquals(
                0,
                transform.transform(owner, "other/Unplanned", null, null, draw("other/Unplanned", "glGetError"))
                        .length);
        assertEquals(0, transform.transform(owner, "other/Corrupt", null, null, new byte[] {1}).length);
        assertNull(transform.transform(ClassLoader.getPlatformClassLoader(), "zombie/LiveFixture", null, null, draw));
    }

    private static byte[] draw(String name, String call) {
        var type = new ClassWriter(0);
        type.visit(V25, ACC_PUBLIC, name, null, "java/lang/Object", null);
        var method = type.visitMethod(ACC_PUBLIC | ACC_STATIC, "draw", "()I", null, null);
        method.visitCode();
        method.visitMethodInsn(INVOKESTATIC, "org/lwjgl/opengl/GL11", call, "()I", false);
        method.visitInsn(IRETURN);
        method.visitMaxs(1, 0);
        method.visitEnd();
        return type.toByteArray();
    }

    private static byte[] frame() {
        var type = new ClassWriter(0);
        type.visit(V25, ACC_PUBLIC, "zombie/core/opengl/RenderThread", null, "java/lang/Object", null);
        var method = type.visitMethod(ACC_PRIVATE | ACC_STATIC, "lockStepRenderStep", "()Z", null, null);
        method.visitCode();
        method.visitInsn(ACONST_NULL);
        method.visitMethodInsn(INVOKEVIRTUAL, "zombie/core/SpriteRenderer", "postRender", "()V", false);
        method.visitInsn(ICONST_1);
        method.visitMethodInsn(INVOKESTATIC, "org/lwjglx/opengl/Display", "update", "(Z)V", false);
        method.visitInsn(ICONST_1);
        method.visitInsn(IRETURN);
        method.visitMaxs(1, 0);
        method.visitEnd();
        return type.toByteArray();
    }
}
