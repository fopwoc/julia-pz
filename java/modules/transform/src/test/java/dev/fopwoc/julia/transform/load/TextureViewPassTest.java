package dev.fopwoc.julia.transform.load;

import static org.junit.jupiter.api.Assertions.*;

import dev.fopwoc.julia.transform.Endpoint;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

class TextureViewPassTest {
    private static final String RESOURCES = "com.gtnewhorizons.angelica.sdlgpu.resource.ResourceManager";

    @Test
    void guardsBothAllocationAndRemovalBeforeStorageCanBeLost() throws IOException {
        var adapted = node(TextureViewPass.adapt(RESOURCES, bytes(RESOURCES)));
        for (String name : new String[] {"createTextureCore", "releaseTextureHandleForRealloc"}) {
            var method = adapted.methods.stream()
                    .filter(m -> m.name.equals(name))
                    .findFirst()
                    .orElseThrow();
            MethodInsnNode first = null;
            for (var instruction : method.instructions)
                if (instruction instanceof MethodInsnNode call) {
                    first = call;
                    break;
                }
            assertNotNull(first);
            assertEquals(Endpoint.TEXTURE_VIEW_HOOKS.owner(), first.owner);
            assertEquals("beforeAllocation", first.name);
        }
    }

    @Test
    void rejectsChangedUpstreamOwnershipAnchors() throws IOException {
        var altered = node(bytes(RESOURCES));
        altered.methods.removeIf(m -> m.name.equals("releaseTextureHandleAfterPendingUploads"));
        var writer = new ClassWriter(0);
        altered.accept(writer);
        assertThrows(IllegalStateException.class, () -> TextureViewPass.adapt(RESOURCES, writer.toByteArray()));
    }

    @Test
    void removesTheMinecraftShrinkWorkaroundFromDeletion() throws IOException {
        String glsm = "com.gtnewhorizons.angelica.glsm.GLStateManager";
        var adapted = node(TextureViewPass.adapt(glsm, bytes(glsm)));
        var method = adapted.methods.stream()
                .filter(m -> m.name.equals("deferDeleteTexture"))
                .findFirst()
                .orElseThrow();
        assertEquals(3, method.instructions.size());
        var call = (MethodInsnNode) method.instructions.get(1);
        assertEquals(Endpoint.TEXTURE_VIEW_HOOKS.owner(), call.owner);
        assertEquals("delete", call.name);
        assertTrue(method.tryCatchBlocks.isEmpty());
    }

    private static byte[] bytes(String name) throws IOException {
        try (var stream = TextureViewPassTest.class.getResourceAsStream("/" + name.replace('.', '/') + ".class")) {
            assertNotNull(stream);
            return stream.readAllBytes();
        }
    }

    private static ClassNode node(byte[] bytes) {
        var node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }
}
