package dev.julia.transform.prepare;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RuntimeProcessTest {
    @TempDir
    Path directory;

    @Test
    void verifiesBytecodeAndLoaderBoundaryInFreshHeadlessJvm() throws Exception {
        Path log = directory.resolve("runtime.log");
        var process = new ProcessBuilder(
                        Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                        "-Xverify:all",
                        "-Djava.awt.headless=true",
                        "-XX:-CreateCoredumpOnCrash",
                        "-cp",
                        System.getProperty("fixture.classpath"),
                        RuntimeFixtureMain.class.getName())
                .redirectErrorStream(true)
                .redirectOutput(log.toFile())
                .start();
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Synthetic runtime subprocess timed out");
            assertEquals(0, process.exitValue(), () -> read(log));
            assertTrue(Files.readString(log).contains("PASS: direct call, lambda, array overload"));
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
        } catch (java.io.IOException failure) {
            return failure.toString();
        }
    }
}
