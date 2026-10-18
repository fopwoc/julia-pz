package dev.fopwoc.julia.backend.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GlDispatchTest {
    @TempDir
    Path directory;

    @Test
    void clearsNativeDispatchAndDestroysProbeFunctionProvider() throws Exception {
        Path source = directory.resolve("org/lwjgl/opengl");
        Files.createDirectories(source);
        Path capabilities = source.resolve("GLCapabilities.java");
        Path gl = source.resolve("GL.java");
        Files.writeString(capabilities, "package org.lwjgl.opengl; public final class GLCapabilities {}");
        Files.writeString(gl, """
                package org.lwjgl.opengl;
                public final class GL {
                    public static boolean dispatchCleared;
                    public static boolean providerDestroyed;
                    public static void setCapabilities(GLCapabilities value) {
                        if (value != null) throw new AssertionError();
                        dispatchCleared = true;
                    }
                    public static void destroy() {
                        if (!dispatchCleared) throw new AssertionError("destroy before clearing capabilities");
                        providerDestroyed = true;
                    }
                }
                """);
        assertEquals(
                0,
                ToolProvider.getSystemJavaCompiler()
                        .run(null, null, null, "-d", directory.toString(), capabilities.toString(), gl.toString()));
        String resource = "dev/fopwoc/julia/backend/runtime/GlDispatch.class";
        Path helper = directory.resolve(resource);
        Files.createDirectories(helper.getParent());
        try (var bytes = GlDispatchTest.class.getResourceAsStream("/" + resource)) {
            assertNotNull(bytes);
            Files.copy(bytes, helper);
        }
        try (var loader =
                new URLClassLoader(new URL[] {directory.toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
            Class.forName("dev.fopwoc.julia.backend.runtime.GlDispatch", true, loader)
                    .getMethod("release")
                    .invoke(null);
            Class<?> bindings = Class.forName("org.lwjgl.opengl.GL", false, loader);
            assertTrue(bindings.getField("dispatchCleared").getBoolean(null));
            assertTrue(bindings.getField("providerDestroyed").getBoolean(null));
        }
    }
}
