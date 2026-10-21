package dev.fopwoc.julia.backend.diagnostic;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TraceShadersTest {
    @TempDir
    Path output;

    @Test
    void savesExactDistinctSourcesAndDeduplicatesRepeatedPrograms() throws Exception {
        var shaders = new TraceShaders(output.resolve("draw-shaders"));
        String first = shaders.write("#version 330\nvoid main() {}\n");
        assertEquals(first, shaders.write("#version 330\nvoid main() {}\n"));
        assertNotEquals(first, shaders.write("#version 450\nvoid main() {}\n"));
        assertEquals("#version 330\nvoid main() {}\n", Files.readString(output.resolve(first)));
        assertEquals("none", shaders.write(""));
        assertEquals("omitted-size-limit", shaders.write("x".repeat(8 * 1024 * 1024)));
        assertNotEquals("omitted-size-limit", shaders.write("small"));
        try (var files = Files.list(output.resolve("draw-shaders"))) {
            assertEquals(3, files.count());
        }
    }
}
