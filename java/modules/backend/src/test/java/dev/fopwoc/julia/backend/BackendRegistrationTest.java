package dev.fopwoc.julia.backend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gtnewhorizons.angelica.glsm.backend.RenderBackend;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** BackendManager discovers backends by name; a stale entry silently selects upstream's OpenGL fallback. */
class BackendRegistrationTest {
    @Test
    void serviceFileNamesTheHostedBackend() throws IOException, ClassNotFoundException {
        String resource = "META-INF/services/" + RenderBackend.class.getName();
        try (var input = HostedBackend.class.getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(input, resource);
            String declared = new String(input.readAllBytes(), StandardCharsets.UTF_8).strip();
            assertEquals(HostedBackend.class.getName(), declared);
            var type = Class.forName(declared, false, HostedBackend.class.getClassLoader());
            assertTrue(RenderBackend.class.isAssignableFrom(type), declared);
        }
    }
}
