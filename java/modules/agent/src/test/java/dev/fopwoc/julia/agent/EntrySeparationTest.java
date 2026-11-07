package dev.fopwoc.julia.agent;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;

class EntrySeparationTest {
    @Test
    void carriesOnlyTheAgentEntry() throws Exception {
        try (var jar = new JarFile(Path.of(System.getProperty("agent.jar")).toFile())) {
            var attributes = jar.getManifest().getMainAttributes();
            assertEquals(JuliaAgent.class.getName(), attributes.getValue("Premain-Class"));
            assertNull(attributes.getValue("ZB-Preload"));
            assertNotNull(jar.getEntry("dev/fopwoc/julia/launch/Launcher.class"));
            assertNull(jar.getEntry("dev/fopwoc/julia/preload/PreMain.class"));
        }
    }
}
