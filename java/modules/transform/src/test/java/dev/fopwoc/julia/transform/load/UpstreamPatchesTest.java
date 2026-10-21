package dev.fopwoc.julia.transform.load;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.JarURLConnection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;

/** Runs every patch over the Angelica release Julia ships, so an upstream bump cannot leave a patch silently inert. */
class UpstreamPatchesTest {
    @Test
    void everyPatchChangesThePublishedUpstream() throws IOException {
        Set<UpstreamPatch> applied = new HashSet<>();
        for (String anchor : Set.of(
                "com/gtnewhorizons/angelica/glsm/GLStateManager.class",
                "com/gtnewhorizons/angelica/sdlgpu/SDLGPURenderBackend.class")) {
            var url = ClassLoader.getSystemResource(anchor);
            assertNotNull(url, "Angelica is not on the test classpath: " + anchor);
            try (JarFile jar = ((JarURLConnection) url.openConnection()).getJarFile()) {
                for (var entry : jar.stream().toList()) {
                    if (!entry.getName().endsWith(".class")) continue;
                    String name = entry.getName().replace('/', '.').replace(".class", "");
                    byte[] bytes;
                    try (var input = jar.getInputStream(entry)) {
                        bytes = LegacyNamespacePass.adapt(name, input.readAllBytes());
                    }
                    for (var patch : UpstreamPatches.ORDER) {
                        if (!patch.targets(name)) continue;
                        var node = new ClassNode();
                        new ClassReader(bytes).accept(node, 0);
                        if (patch.apply(name, node)) applied.add(patch);
                    }
                }
            }
        }
        var inert = UpstreamPatches.ORDER.stream()
                .filter(patch -> !applied.contains(patch))
                .map(patch -> patch.getClass().getSimpleName())
                .toList();
        assertEquals(List.of(), inert, "Patches that no longer change any upstream class");
    }
}
