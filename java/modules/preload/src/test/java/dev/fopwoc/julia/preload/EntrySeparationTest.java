package dev.fopwoc.julia.preload;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;

class EntrySeparationTest {
    @Test
    void carriesOnlyThePreloadEntry() throws Exception {
        try (var jar = new JarFile(Path.of(System.getProperty("preload.jar")).toFile())) {
            var attributes = jar.getManifest().getMainAttributes();
            assertEquals("true", attributes.getValue("ZB-Preload"));
            assertNull(attributes.getValue("Premain-Class"));
            assertNotNull(jar.getEntry("dev/fopwoc/julia/preload/PreMain.class"));
            assertNotNull(jar.getEntry("dev/fopwoc/julia/launch/Launcher.class"));
            assertNull(jar.getEntry("dev/fopwoc/julia/agent/JuliaAgent.class"));
        }
    }
}
