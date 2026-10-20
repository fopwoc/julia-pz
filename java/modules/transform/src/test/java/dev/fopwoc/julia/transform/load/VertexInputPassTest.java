package dev.fopwoc.julia.transform.load;

import static org.junit.jupiter.api.Assertions.*;

import dev.fopwoc.julia.transform.Endpoint;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

class VertexInputPassTest {
    private static final String CACHE = "com.gtnewhorizons.angelica.sdlgpu.pipeline.PipelineCache";

    @Test
    void typesDisabledAttributesByTheirShaderInput() throws IOException {
        var adapted = new ClassNode();
        new ClassReader(new VertexInputPass().adapt(CACHE, bytes())).accept(adapted, 0);
        var method = adapted.methods.stream()
                .filter(m -> m.name.equals("buildVertexInput"))
                .findFirst()
                .orElseThrow();
        MethodInsnNode hook = null;
        for (var instruction : method.instructions)
            if (instruction instanceof MethodInsnNode call && call.name.equals("defaultFormat")) {
                assertNull(hook);
                hook = call;
            }
        assertNotNull(hook);
        assertEquals(Endpoint.VERTEX_INPUTS.owner(), hook.owner);
        var location = (VarInsnNode) previous(hook);
        var cache = (VarInsnNode) previous(location);
        var components = (VarInsnNode) previous(cache);
        assertEquals(Opcodes.ILOAD, location.getOpcode());
        assertEquals(Opcodes.ALOAD, cache.getOpcode());
        assertEquals(0, cache.var);
        assertEquals(Opcodes.ILOAD, components.getOpcode());
        assertNotEquals(components.var, location.var);
    }

    private static AbstractInsnNode previous(AbstractInsnNode instruction) {
        do instruction = instruction.getPrevious();
        while (instruction.getOpcode() < 0);
        return instruction;
    }

    private static byte[] bytes() throws IOException {
        try (var stream = VertexInputPassTest.class.getResourceAsStream("/" + CACHE.replace('.', '/') + ".class")) {
            assertNotNull(stream);
            return stream.readAllBytes();
        }
    }
}
