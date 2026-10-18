package dev.fopwoc.julia.audit.cli;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AuditMainTest {
    @TempDir
    Path directory;

    @Test
    void rejectsOutputAliasingAnInputJar() throws Exception {
        Path input = directory.resolve("game.jar");
        try (var jar = new JarOutputStream(Files.newOutputStream(input))) {
            jar.finish();
        }
        Path alias = directory.resolve("alias.jar");
        Files.createSymbolicLink(alias, input);
        byte[] original = Files.readAllBytes(input);
        var errors = new ByteArrayOutputStream();
        int result = AuditMain.run(
                new String[] {"--output", alias.toString(), input.toString()},
                new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(errors));
        assertEquals(1, result);
        assertTrue(errors.toString().contains("overwrite an input JAR"));
        assertArrayEquals(original, Files.readAllBytes(input));
    }

    @Test
    void rejectsMissingOutputArgument() {
        assertEquals(
                1,
                AuditMain.run(
                        new String[] {"--output"},
                        new PrintStream(new ByteArrayOutputStream()),
                        new PrintStream(new ByteArrayOutputStream())));
    }
}
