package dev.fopwoc.julia.zomboid;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HostGlStateTest {
    private static final String DISPATCH = "julia.test.nativeDispatch";

    @TempDir
    Path directory;

    @BeforeEach
    void compileIndependentBindings() throws Exception {
        Path source = directory.resolve("org/lwjgl/opengl");
        Files.createDirectories(source);
        Path capabilities = source.resolve("GLCapabilities.java");
        Path gl = source.resolve("GL.java");
        Path gl11 = source.resolve("GL11.java");
        Files.writeString(capabilities, """
                package org.lwjgl.opengl;
                public final class GLCapabilities {
                    public final long address;
                    public GLCapabilities(long address) { this.address = address; }
                }
                """);
        Files.writeString(gl, """
                package org.lwjgl.opengl;
                public final class GL {
                    private static final ThreadLocal<GLCapabilities> CURRENT = new ThreadLocal<>();
                    public static GLCapabilities getCapabilities() { return CURRENT.get(); }
                    public static void setCapabilities(GLCapabilities capabilities) {
                        CURRENT.set(capabilities);
                        System.getProperties().put("julia.test.nativeDispatch", capabilities.address);
                    }
                }
                """);
        Files.writeString(gl11, """
                package org.lwjgl.opengl;
                public final class GL11 {
                    public static int glGetInteger(int parameter) {
                        if (parameter != 0x8069) throw new AssertionError("unexpected query");
                        long nativeDispatch = (Long) System.getProperties().get("julia.test.nativeDispatch");
                        if (nativeDispatch != GL.getCapabilities().address) {
                            throw new AssertionError("native dispatch was not restored");
                        }
                        return 17;
                    }
                }
                """);
        assertEquals(
                0,
                ToolProvider.getSystemJavaCompiler()
                        .run(
                                null,
                                null,
                                null,
                                "-d",
                                directory.toString(),
                                capabilities.toString(),
                                gl.toString(),
                                gl11.toString()));
    }

    @Test
    void republishesHostDispatchDespiteUnchangedHostJavaCapabilities() throws Exception {
        Object previous = System.getProperties().get(DISPATCH);
        try (var host = bindings();
                var probe = bindings()) {
            Object original = install(host, 1234);
            try (var saved = HostGlState.capture(host)) {
                install(probe, 0xDEAD000000000001L);
                assertSame(original, capabilities(host));
                assertEquals(0xDEAD000000000001L, System.getProperties().get(DISPATCH));
                saved.restoreAndVerify();
                assertEquals(1234L, System.getProperties().get(DISPATCH));
            }
            assertSame(original, capabilities(host));
        } finally {
            restoreProperty(previous);
        }
    }

    @Test
    void restoresAfterProbeThrows() throws Exception {
        Object previous = System.getProperties().get(DISPATCH);
        try (var host = bindings();
                var probe = bindings()) {
            Object original = install(host, 5678);
            assertThrows(IllegalArgumentException.class, () -> {
                try (var saved = HostGlState.capture(host)) {
                    assertNotNull(saved);
                    install(probe, 0xDEAD000000000001L);
                    throw new IllegalArgumentException("probe failed");
                }
            });
            assertSame(original, capabilities(host));
            assertEquals(5678L, System.getProperties().get(DISPATCH));
        } finally {
            restoreProperty(previous);
        }
    }

    @Test
    void rejectsRestorationFromAnotherThread() throws Exception {
        Object previous = System.getProperties().get(DISPATCH);
        try (var host = bindings()) {
            install(host, 1234);
            try (var saved = HostGlState.capture(host)) {
                var failure = new AtomicReference<Throwable>();
                Thread thread = Thread.ofPlatform().start(() -> {
                    try {
                        saved.restoreAndVerify();
                    } catch (Throwable exception) {
                        failure.set(exception);
                    }
                });
                thread.join();
                assertInstanceOf(IllegalStateException.class, failure.get());
            }
        } finally {
            restoreProperty(previous);
        }
    }

    @Test
    void rejectsCaptureWithoutHostCapabilities() throws Exception {
        try (var host = bindings()) {
            assertThrows(IllegalStateException.class, () -> HostGlState.capture(host));
        }
    }

    private URLClassLoader bindings() throws Exception {
        return new URLClassLoader(new URL[] {directory.toUri().toURL()}, ClassLoader.getPlatformClassLoader());
    }

    private static Object install(ClassLoader loader, long address) throws Exception {
        Class<?> gl = Class.forName("org.lwjgl.opengl.GL", true, loader);
        Class<?> type = gl.getMethod("getCapabilities").getReturnType();
        Object capabilities = type.getConstructor(long.class).newInstance(address);
        gl.getMethod("setCapabilities", type).invoke(null, capabilities);
        return capabilities;
    }

    private static Object capabilities(ClassLoader loader) throws Exception {
        return Class.forName("org.lwjgl.opengl.GL", true, loader)
                .getMethod("getCapabilities")
                .invoke(null);
    }

    private static void restoreProperty(Object previous) {
        if (previous == null) System.getProperties().remove(DISPATCH);
        else System.getProperties().put(DISPATCH, previous);
    }
}
