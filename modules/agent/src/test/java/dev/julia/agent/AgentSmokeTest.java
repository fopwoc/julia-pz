package dev.julia.agent;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import zombie.Sample;
import static org.junit.jupiter.api.Assertions.*;

class AgentSmokeTest {
    @TempDir Path directory;

    @Test
    void attachesToSeparateJvmAndObservesWithoutChangingBehavior() throws Exception {
        Path report = directory.resolve("runtime.tsv");
        Path log = directory.resolve("process.log");
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        Path fixtures = Path.of(Sample.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        var builder = new ProcessBuilder(java.toString(), "-XX:-CreateCoredumpOnCrash",
                "-javaagent:" + System.getProperty("agent.jar") + "=" + report,
                "-cp", fixtures.toString(), "zombie.Sample").redirectErrorStream(true).redirectOutput(log.toFile());
        builder.environment().put("LOG_LEVEL", "info");
        var process = builder.start();
        try {
            assertTrue(process.waitFor(20, TimeUnit.SECONDS), "Agent subprocess timed out");
            assertEquals(0, process.exitValue(), () -> read(log));
            String output = Files.readString(log);
            assertTrue(output.contains("Original application behavior preserved"), output);
            assertTrue(output.contains("Julia ready in audit mode"), output);
            var lines = Files.readAllLines(report);
            assertEquals(2, lines.size());
            assertTrue(lines.get(1).contains("\torg/lwjgl/opengl/GL11\tglGetError\t()I\t"));
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
        }
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (java.io.IOException exception) {
            return exception.toString();
        }
    }
}
