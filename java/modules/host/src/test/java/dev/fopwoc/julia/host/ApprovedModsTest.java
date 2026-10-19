package dev.fopwoc.julia.host;

import static org.junit.jupiter.api.Assertions.*;

import dev.fopwoc.julia.runtime.ModApprovalHooks;
import dev.fopwoc.julia.transform.prepare.ArchivePreparation;
import dev.fopwoc.julia.utils.io.Sha256;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

class ApprovedModsTest implements Opcodes {
    @TempDir
    Path directory;

    @AfterEach
    void clearStatus() {
        System.clearProperty("julia.mods.blocked");
    }

    @Test
    void publishesCompleteSupportedModsAndPreservesStartupEntries() throws Exception {
        var fixture = baseline(() -> new Class<?>[0]);
        byte[] draw = draw("mod/Supported", "glGetError");
        Path mod = jar("supported.jar", Map.of("mod/Supported", draw));
        var request = new ModApprovalHooks.Request(mod, "mod", Sha256.of(mod));
        assertTrue(fixture.mods.prepare(request));
        assertTrue(fixture.mods.prepare(request), "Repeated phases must not create duplicate consumers");
        assertTrue(fixture.transformer.transform(getClass().getClassLoader(), "mod/Supported", null, null, draw).length
                > 1);
        assertTrue(fixture.transformer.transform(
                                getClass().getClassLoader(),
                                "zombie/Baseline",
                                null,
                                null,
                                draw("zombie/Baseline", "glGetError"))
                        .length
                > 1);
        assertEquals("", System.getProperty("julia.mods.blocked"));
    }

    @Test
    void rejectsWholeUnsupportedArchiveBeforePublishingAnyOfItsClasses() throws Exception {
        var fixture = baseline(() -> new Class<?>[0]);
        byte[] good = draw("mod/Good", "glGetError");
        Path mod = jar("unsupported.jar", Map.of("mod/Good", good, "mod/Bad", draw("mod/Bad", "glUnsupported")));
        String hash = Sha256.of(mod);
        assertFalse(fixture.mods.prepare(new ModApprovalHooks.Request(mod, "unsupported", hash)));
        assertEquals(
                1, fixture.transformer.transform(getClass().getClassLoader(), "mod/Good", null, null, good).length);
        String report = Files.readString(directory.resolve("mod-" + hash + ".txt"));
        assertTrue(report.contains("mod/Bad"));
        assertTrue(report.contains("glUnsupported"));
        assertTrue(System.getProperty("julia.mods.blocked").contains("unsupported"));
    }

    @Test
    void refusesChangedApprovalAlreadyLoadedGraphicsAndDuplicateDefinitions() throws Exception {
        var fixture = baseline(() -> new Class<?>[] {ApprovedModsTest.class});
        Path mod = jar("changed.jar", Map.of("mod/Changed", draw("mod/Changed", "glGetError")));
        assertFalse(fixture.mods.prepare(new ModApprovalHooks.Request(mod, "changed", "not-the-approved-hash")));
        Path loaded = jar(
                "loaded.jar",
                Map.of(
                        getClass().getName().replace('.', '/'),
                        draw(getClass().getName().replace('.', '/'), "glGetError")));
        assertFalse(fixture.mods.prepare(new ModApprovalHooks.Request(loaded, "loaded", Sha256.of(loaded))));
        Path duplicate = jar("duplicate.jar", Map.of("zombie/Baseline", draw("zombie/Baseline", "glGetError")));
        assertFalse(fixture.mods.prepare(new ModApprovalHooks.Request(duplicate, "duplicate", Sha256.of(duplicate))));
        assertTrue(Files.readString(directory.resolve("mod-" + Sha256.of(duplicate) + ".txt"))
                .contains("DUPLICATE_CLASS"));
    }

    @Test
    void refusesHotReplacementOfAnAlreadyPreparedArchive() throws Exception {
        var fixture = baseline(() -> new Class<?>[0]);
        Path mod = jar("active.jar", Map.of("mod/Old", draw("mod/Old", "glGetError")));
        assertTrue(fixture.mods.prepare(new ModApprovalHooks.Request(mod, "active", Sha256.of(mod))));
        jar("active.jar", Map.of("mod/New", draw("mod/New", "glGetError")));
        assertFalse(fixture.mods.prepare(new ModApprovalHooks.Request(mod, "active", Sha256.of(mod))));
    }

    private record Fixture(PreparedTransformer transformer, ApprovedMods mods) {}

    private Fixture baseline(java.util.function.Supplier<Class<?>[]> loaded) throws Exception {
        Path archive = jar(
                "game.jar",
                Map.of(
                        "zombie/Baseline",
                        draw("zombie/Baseline", "glGetError"),
                        "zombie/core/opengl/RenderThread",
                        frame()));
        var plan = ArchivePreparation.inspect(List.of(archive));
        var transformer = new PreparedTransformer(plan, getClass().getClassLoader());
        return new Fixture(transformer, new ApprovedMods(plan, transformer, loaded, directory));
    }

    private Path jar(String name, Map<String, byte[]> classes) throws Exception {
        Path path = directory.resolve(name);
        try (var jar = new JarOutputStream(Files.newOutputStream(path))) {
            for (var entry : classes.entrySet()) {
                jar.putNextEntry(new JarEntry(entry.getKey() + ".class"));
                jar.write(entry.getValue());
                jar.closeEntry();
            }
        }
        return path;
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
        var method = type.visitMethod(ACC_PUBLIC | ACC_STATIC, "lockStepRenderStep", "()Z", null, null);
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
