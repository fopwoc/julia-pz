package dev.fopwoc.julia.agent;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.jar.JarInputStream;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;

class RendererPackagingTest {
    @Test
    void bundlesExactlyTheSourceBuiltRenderersWithoutMinecraftStubs() throws Exception {
        try (var agent = new ZipFile(System.getProperty("agent.jar"))) {
            assertFalse(
                    agent.stream()
                            .anyMatch(entry -> entry.getName().startsWith("lib/launchwrapper-")
                                    || entry.getName().startsWith("lib/RetroFuturaBootstrap-")),
                    "Minecraft launcher dependency leaked");
            for (String module : new String[] {"glsm", "sdl-gpu"}) {
                var built = Path.of(System.getProperty("renderer." + module + ".jar"));
                var nested = agent.stream()
                        .filter(entry -> entry.getName().startsWith("lib/" + module + "-")
                                && entry.getName().endsWith(".jar"))
                        .toList();
                assertEquals(1, nested.size(), "Duplicate or missing " + module);
                byte[] bytes;
                try (var input = agent.getInputStream(nested.getFirst())) {
                    bytes = input.readAllBytes();
                }
                assertArrayEquals(Files.readAllBytes(built), bytes, "Bundled renderer is not the local source build");
                var names = new HashSet<String>();
                try (var jar = new JarInputStream(new ByteArrayInputStream(bytes))) {
                    for (var entry = jar.getNextJarEntry(); entry != null; entry = jar.getNextJarEntry()) {
                        names.add(entry.getName());
                        if (entry.getName().endsWith(".class")) {
                            String constants = new String(jar.readAllBytes(), StandardCharsets.ISO_8859_1);
                            assertFalse(constants.contains("net/minecraft/launchwrapper/"), entry.getName());
                            assertFalse(constants.contains("com/gtnewhorizons/retrofuturabootstrap/"), entry.getName());
                            assertFalse(
                                    constants.contains("org/embeddedt/embeddium/impl/render/shader/ShaderLoader"),
                                    entry.getName());
                        }
                    }
                }
                assertTrue(names.contains("META-INF/licenses/angelica/LICENSE"));
                assertFalse(
                        names.stream().anyMatch(name -> name.startsWith("com/gtnewhorizons/angelica/glsm/loading/")),
                        "Minecraft class-loading integration leaked");
                assertFalse(names.stream().anyMatch(name -> name.startsWith("net/minecraft/")), "Compile stubs leaked");
                String mainClass = module.equals("glsm")
                        ? "com/gtnewhorizons/angelica/glsm/GLStateManager.class"
                        : "com/gtnewhorizons/angelica/sdlgpu/SDLGPURenderBackend.class";
                assertTrue(names.contains(mainClass), mainClass);
            }
        }
    }

    @Test
    void shipsNoGuavaClassesOrReferences() throws Exception {
        try (var agent = new ZipFile(System.getProperty("agent.jar"))) {
            for (var library : agent.stream()
                    .filter(entry -> entry.getName().endsWith(".jar"))
                    .toList()) {
                try (var jar = new JarInputStream(agent.getInputStream(library))) {
                    for (var entry = jar.getNextJarEntry(); entry != null; entry = jar.getNextJarEntry()) {
                        if (!entry.getName().endsWith(".class")) continue;
                        String location = library.getName() + "!/" + entry.getName();
                        assertFalse(entry.getName().startsWith("com/google/common/"), location);
                        String constants = new String(jar.readAllBytes(), StandardCharsets.ISO_8859_1);
                        assertFalse(constants.contains("com/google/common/"), location);
                    }
                }
            }
        }
    }
}
