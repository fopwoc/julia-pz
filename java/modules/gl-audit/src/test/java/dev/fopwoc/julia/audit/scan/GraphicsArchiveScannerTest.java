package dev.fopwoc.julia.audit.scan;

import static java.lang.classfile.ClassFile.ACC_STATIC;
import static java.lang.constant.ConstantDescs.CD_void;
import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GraphicsArchiveScannerTest {
    @TempDir
    Path directory;

    @Test
    void selectsRuntimeVariantAndExcludesEmbeddedBindings() throws Exception {
        Path path = directory.resolve("game.jar");
        var manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.MULTI_RELEASE, "true");
        try (var jar = new JarOutputStream(Files.newOutputStream(path), manifest)) {
            add(jar, "example/Renderer.class", "base");
            add(jar, "META-INF/versions/25/example/Renderer.class", "java25");
            add(jar, "META-INF/versions/99/example/Renderer.class", "future");
            add(jar, "org/lwjgl/opengl/GL11.class", "bindingInternals");
        }
        var audit = new GraphicsArchiveScanner().scan(path);
        assertEquals(1, audit.classesScanned());
        assertEquals(1, audit.bindingsExcluded());
        assertEquals(1, audit.references().size());
        assertEquals("java25", audit.references().getFirst().target().name());
        assertEquals(64, audit.sha256().length());
        assertEquals("game.jar", audit.archive());
    }

    @Test
    void identifiesMalformedEntryInsteadOfSilentlySkippingIt() throws Exception {
        Path path = directory.resolve("broken.jar");
        try (var jar = new JarOutputStream(Files.newOutputStream(path))) {
            jar.putNextEntry(new JarEntry("broken/Renderer.class"));
            jar.write(new byte[] {0, 1});
            jar.closeEntry();
        }
        var error = assertThrows(IOException.class, () -> new GraphicsArchiveScanner().scan(path));
        assertTrue(error.getMessage().endsWith("!broken/Renderer.class"));
    }

    private static void add(JarOutputStream jar, String name, String target) throws Exception {
        var bytes = ClassFile.of()
                .build(
                        ClassDesc.of("example.Renderer"),
                        builder -> builder.withMethodBody(
                                "render",
                                MethodTypeDesc.of(CD_void),
                                ACC_STATIC,
                                code -> code.invokestatic(
                                                ClassDesc.of("org.lwjgl.opengl.GL11"),
                                                target,
                                                MethodTypeDesc.of(CD_void))
                                        .return_()));
        jar.putNextEntry(new JarEntry(name));
        jar.write(bytes);
        jar.closeEntry();
    }
}
