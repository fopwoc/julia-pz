package dev.fopwoc.julia.transform.load;

import static org.junit.jupiter.api.Assertions.*;

import dev.fopwoc.julia.transform.Endpoint;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

class BufferTexturePassTest {
    private static final String BINDER = "com.gtnewhorizons.angelica.sdlgpu.sampler.SamplerBinder";

    @Test
    void passesSlotAndStageToSamplerLookup() throws IOException {
        var adapted = new ClassNode();
        new ClassReader(BufferTexturePass.adapt(BINDER, bytes())).accept(adapted, 0);
        var method = adapted.methods.stream()
                .filter(m -> m.name.equals("bindStageSamplers"))
                .findFirst()
                .orElseThrow();
        MethodInsnNode lookup = null;
        for (var instruction : method.instructions)
            if (instruction instanceof MethodInsnNode call && call.name.equals("samplerBinding")) lookup = call;
        assertNotNull(lookup);
        assertEquals(Endpoint.BUFFER_TEXTURE_HOOKS.owner(), lookup.owner);
        assertTrue(lookup.desc.endsWith(";IIZ)I"));
        var fragment = (VarInsnNode) lookup.getPrevious();
        var slot = (VarInsnNode) fragment.getPrevious();
        var unit = (VarInsnNode) slot.getPrevious();
        assertEquals(Opcodes.ILOAD, fragment.getOpcode());
        assertEquals(7, fragment.var);
        assertEquals(Opcodes.ILOAD, slot.getOpcode());
        assertNotEquals(unit.var, slot.var);
    }

    private static byte[] bytes() throws IOException {
        try (var stream = BufferTexturePassTest.class.getResourceAsStream("/" + BINDER.replace('.', '/') + ".class")) {
            assertNotNull(stream);
            return stream.readAllBytes();
        }
    }
}
