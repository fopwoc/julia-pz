package dev.fopwoc.julia.profiling.agent;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.classfile.ClassFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProfilerAgentTest {
    @Test
    void installsTheFrameHookInAFreshJvmWithoutAnyRenderer(@TempDir Path run) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin/java").toString();
        String fixture = Path.of(zombie.core.opengl.RenderThread.class
                        .getProtectionDomain()
                        .getCodeSource()
                        .getLocation()
                        .toURI())
                .toString();
        var process = new ProcessBuilder(
                        java,
                        "-javaagent:" + System.getProperty("profiler.jar") + "=opengl," + run,
                        "-cp",
                        fixture,
                        "zombie.core.opengl.RenderThread")
                .redirectErrorStream(true)
                .redirectOutput(run.resolve("process.log").toFile())
                .start();
        assertTrue(process.waitFor(15, TimeUnit.SECONDS));
        assertEquals(0, process.exitValue(), Files.readString(run.resolve("process.log")));
        assertEquals("READY", Files.readString(run.resolve("status.txt")).strip());
    }

    @Test
    void recordsFramesFromTheHookAndFlushesOffTheFrameThread(@TempDir Path run) throws Exception {
        try (var controller = new CaptureController(run)) {
            controller.frameOwnerReady();
            controller.start();
            controller.record("forest", Duration.ZERO, Duration.ofMillis(50));
            assertNull(controller.finished());
            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (controller.finished() == null && System.nanoTime() < deadline) {
                ProfilingHooks.begin();
                ProfilingHooks.complete(true);
                Thread.sleep(1);
            }
            assertEquals("COMPLETE", controller.finished());
        }
        var summary = new Properties();
        try (var reader = Files.newBufferedReader(run.resolve("captures/forest/summary.properties"))) {
            summary.load(reader);
        }
        assertEquals("COMPLETE", summary.getProperty("status"));
        assertTrue(Integer.parseInt(summary.getProperty("frames")) > 0);
    }

    @Test
    void logicHookRunsOnEveryStepAndVerifies() throws Exception {
        byte[] bytes;
        try (var input = getClass().getResourceAsStream("/zombie/GameWindow.class")) {
            assertNotNull(input);
            bytes = input.readAllBytes();
        }
        byte[] transformed = LogicInstrumentation.instrument(bytes, getClass().getClassLoader());
        assertTrue(ClassFile.of().verify(transformed).isEmpty());
        assertThrows(
                IllegalArgumentException.class,
                () -> LogicInstrumentation.instrument(new byte[0], getClass().getClassLoader()));
    }

    @Test
    void transformedBytecodeVerifiesAndDuplicateInstrumentationIsRejected() throws Exception {
        byte[] bytes;
        try (var input = getClass().getResourceAsStream("/zombie/core/opengl/RenderThread.class")) {
            assertNotNull(input);
            bytes = input.readAllBytes();
        }
        byte[] transformed = FrameInstrumentation.instrument(bytes);
        assertTrue(ClassFile.of().verify(transformed).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> FrameInstrumentation.instrument(transformed));
    }
}
