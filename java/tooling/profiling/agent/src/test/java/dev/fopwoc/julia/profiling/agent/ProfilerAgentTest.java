package dev.fopwoc.julia.profiling.agent;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.classfile.ClassFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProfilerAgentTest {
    @Test
    void observesSyntheticCompletedFramesInAFreshJvmWithoutAnyRenderer(@TempDir Path run) throws Exception {
        Files.writeString(
                run.resolve("control.properties"),
                "id=fixture\naction=start\nscenario=forest\nwarmup_ms=0\nduration_ms=100\n");
        String java = Path.of(System.getProperty("java.home"), "bin/java").toString();
        String fixture = Path.of(zombie.core.opengl.RenderThread.class
                        .getProtectionDomain()
                        .getCodeSource()
                        .getLocation()
                        .toURI())
                .toString();
        var process = new ProcessBuilder(
                        java,
                        "-javaagent:" + System.getProperty("profiler.jar") + "=" + run,
                        "-cp",
                        fixture,
                        "zombie.core.opengl.RenderThread")
                .redirectErrorStream(true)
                .redirectOutput(run.resolve("process.log").toFile())
                .start();
        assertTrue(process.waitFor(15, TimeUnit.SECONDS));
        assertEquals(0, process.exitValue(), Files.readString(run.resolve("process.log")));
        var capture = run.resolve("captures/fixture-forest");
        var summary = new Properties();
        try (var reader = Files.newBufferedReader(capture.resolve("summary.properties"))) {
            summary.load(reader);
        }
        assertEquals("COMPLETE", summary.getProperty("status"));
        assertTrue(Integer.parseInt(summary.getProperty("frames")) > 0);
        assertTrue(Files.readString(run.resolve("status.txt")).startsWith("COMPLETE"));
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
