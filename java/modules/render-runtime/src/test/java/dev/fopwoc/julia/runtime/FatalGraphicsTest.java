package dev.fopwoc.julia.runtime;

import static org.junit.jupiter.api.Assertions.*;

import dev.fopwoc.julia.runtime.fixture.FatalGraphicsProcess;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FatalGraphicsTest {
    @TempDir
    Path directory;

    @Test
    void unsupportedCallsCannotBeCaughtAndReportsSurviveImmediateHalt() throws Exception {
        Path output = run("unsupported", 70);
        String report = Files.readString(output.resolve("fatal-error.txt"));
        assertTrue(report.contains("GL45.glUnsupported"), report);
        assertTrue(report.contains("UnsupportedOperationException"), report);
        assertTrue(report.contains("thread=main"), report);
        assertTrue(report.contains("pid="), report);
    }

    @Test
    void backendAndResolutionFailuresAreFatalForPrimitiveAndVoidTargets() throws Exception {
        for (String mode : new String[] {"backend", "backend-void", "resolve"}) {
            Path output = run(mode, 70);
            String report = Files.readString(output.resolve("fatal-error.txt"));
            assertTrue(report.contains("fixture/Backend"), report);
            assertTrue(report.contains(mode.equals("resolve") ? "fixture target missing" : "exploded"), report);
        }
    }

    @Test
    void shaderTranslationFailureCannotBeCaughtByAMod() throws Exception {
        Path output = run("shader", 70);
        String report = Files.readString(output.resolve("fatal-error.txt"));
        assertTrue(report.contains("brokenShader"), report);
        assertTrue(report.contains("UnsupportedOperationException"), report);
        assertTrue(report.contains("Too many fragment shader texture samplers (33, maximum 32)"), report);
    }

    @Test
    void frameFailuresHaltBeforeCleanupOrAnyShutdownHooks() throws Exception {
        for (String mode : new String[] {"begin", "present", "abort"}) {
            Path output = run(mode, 70);
            String report = Files.readString(output.resolve("fatal-error.txt"));
            assertTrue(report.contains("fixture"), report);
            assertFalse(Files.exists(output.resolve("cleanup")));
        }
    }

    @Test
    void reportWriteFailureStillLogsOriginalCauseAndHalts() throws Exception {
        Path output = run("report-io", 70);
        String log = Files.readString(output.resolve("stderr.txt"));
        assertTrue(log.contains("GL45.glUnsupported"), log);
        assertTrue(log.contains("NoSuchFileException"), log);
    }

    private Path run(String mode, int expectedExit) throws Exception {
        Path output = Files.createDirectory(directory.resolve(mode));
        String classpath = Path.of(FatalGraphics.class
                        .getProtectionDomain()
                        .getCodeSource()
                        .getLocation()
                        .toURI())
                + File.pathSeparator
                + Path.of(FatalGraphicsProcess.class
                        .getProtectionDomain()
                        .getCodeSource()
                        .getLocation()
                        .toURI());
        var process = new ProcessBuilder(
                        Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                        "-cp",
                        classpath,
                        FatalGraphicsProcess.class.getName(),
                        output.toString(),
                        mode)
                .redirectErrorStream(true)
                .redirectOutput(output.resolve("stderr.txt").toFile())
                .start();
        try {
            assertTrue(process.waitFor(15, TimeUnit.SECONDS), "Fatal process did not terminate: " + mode);
            assertEquals(expectedExit, process.exitValue(), () -> {
                try {
                    return Files.readString(output.resolve("stderr.txt"));
                } catch (Exception failure) {
                    return failure.toString();
                }
            });
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
        assertFalse(Files.exists(output.resolve("caught")), "A mod caught the fatal failure");
        assertFalse(Files.exists(output.resolve("continued")), "Execution continued after a fatal failure");
        assertFalse(Files.exists(output.resolve("shutdown-hook")), "A shutdown hook ran after a fatal failure");
        return output;
    }
}
