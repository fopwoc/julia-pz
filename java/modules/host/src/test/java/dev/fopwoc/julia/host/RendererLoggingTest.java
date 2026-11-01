package dev.fopwoc.julia.host;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RendererLoggingTest {
    @TempDir
    Path directory;

    @ParameterizedTest
    @ValueSource(strings = {"debug", "info", "warn", "error", "default", "invalid"})
    void configuresActualIsolatedLoggersWithoutChangingTheGame(String level) throws Exception {
        Path output = directory.resolve("logging.txt");
        var builder = new ProcessBuilder(
                        Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                        "-cp",
                        System.getProperty("logging.classpath"),
                        RendererLoggingFixtureMain.class.getName())
                .redirectErrorStream(true)
                .redirectOutput(output.toFile());
        if (level.equals("default")) builder.environment().remove("LOG_LEVEL");
        else builder.environment().put("LOG_LEVEL", level);
        var process = builder.start();
        try {
            assertTrue(process.waitFor(20, TimeUnit.SECONDS), "Logging fixture timed out");
            String text = Files.readString(output);
            if (level.equals("invalid")) {
                assertNotEquals(0, process.exitValue(), text);
                assertTrue(text.contains("LOG_LEVEL must be debug, info, warn, or error"), text);
                assertFalse(text.contains("logging-fixture-ok"), text);
                return;
            }
            assertEquals(0, process.exitValue(), text);
            assertTrue(text.contains("logging-fixture-ok"), text);
            var levels = List.of("debug", "info", "warn", "error");
            int threshold = levels.indexOf(level.equals("default") ? "error" : level);
            for (String family : List.of("library", "owned", "helper")) {
                for (int i = 0; i < levels.size(); i++) {
                    String message = family + "-" + levels.get(i) + " value";
                    assertEquals(i >= threshold ? 2 : 0, occurrences(text, message), message + "\n" + text);
                }
            }
            assertEquals(2, occurrences(text, "library-fatal value"), text);
            assertEquals(2, occurrences(text, "fault value"), text);
            assertEquals(2, occurrences(text, "java.lang.IllegalStateException: logging-fixture-failure"), text);
            assertEquals(
                    2,
                    occurrences(text, "SEVERE dev.fopwoc.julia.renderer.arbitrary-renderer/name: library-error value"),
                    text);
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
        }
    }

    private static int occurrences(String text, String value) {
        return text.split(Pattern.quote(value), -1).length - 1;
    }
}
