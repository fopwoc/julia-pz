package dev.fopwoc.julia.transform.load;

import static org.junit.jupiter.api.Assertions.*;

import dev.fopwoc.julia.transform.Endpoint;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

class CompatShaderPassTest {
    private static final String STATE = "com.gtnewhorizons.angelica.glsm.GLStateManager";

    @Test
    void rewritesThePublishedShaderEntryWithoutChangingOtherCompatibilityCalls() throws IOException {
        try (var stream = getClass().getResourceAsStream("/" + STATE.replace('.', '/') + ".class")) {
            assertNotNull(stream);
            var node = new ClassNode();
            new ClassReader(new CompatShaderPass().adapt(STATE, stream.readAllBytes())).accept(node, 0);
            int routed = 0;
            for (var method : node.methods)
                for (var instruction : method.instructions)
                    if (instruction instanceof MethodInsnNode call && Endpoint.COMPAT_SHADER_INPUT.is(call.owner)) {
                        assertEquals("glShaderSource", method.name);
                        assertEquals("(ILjava/lang/CharSequence;)V", method.desc);
                        assertEquals("transform", call.name);
                        assertEquals("(Ljava/lang/String;IZI)Ljava/lang/String;", call.desc);
                        assertEquals(Opcodes.ILOAD, call.getPrevious().getOpcode());
                        routed++;
                    }
            assertEquals(1, routed);
        }
    }

    @Test
    void rejectsUpstreamDriftInsteadOfSilentlyOmittingPreprocessing() {
        var writer = new ClassWriter(0);
        writer.visit(Opcodes.V25, Opcodes.ACC_PUBLIC, STATE.replace('.', '/'), null, "java/lang/Object", null);
        writer.visitEnd();
        assertThrows(IllegalStateException.class, () -> new CompatShaderPass().adapt(STATE, writer.toByteArray()));
    }

    @Test
    void leavesTheTransformerAndGameClassesAlone() {
        byte[] bytes = {0};
        assertSame(
                bytes, new CompatShaderPass().adapt("com.gtnewhorizons.angelica.glsm.CompatShaderTransformer", bytes));
        assertSame(bytes, new CompatShaderPass().adapt("zombie.core.opengl.Shader", bytes));
    }
}
