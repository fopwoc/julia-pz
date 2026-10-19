package dev.fopwoc.julia.transform.load;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

class QuadIndexUploadPassTest {
    private static final String CONVERTER = "com.gtnewhorizons.angelica.glsm.QuadConverter";

    @Test
    void respecifiesOnlyThePublishedScratchIndexUpload() throws IOException {
        try (var stream = getClass().getResourceAsStream("/" + CONVERTER.replace('.', '/') + ".class")) {
            assertNotNull(stream);
            var node = new ClassNode();
            new ClassReader(QuadIndexUploadPass.adapt(CONVERTER, stream.readAllBytes())).accept(node, 0);
            int uploads = 0;
            for (var method : node.methods)
                for (var instruction : method.instructions)
                    if (instruction instanceof MethodInsnNode call) {
                        assertNotEquals("bufferSubData", call.name, "Shared scratch indices must not be overwritten");
                        if (method.name.equals("uploadAndDraw")
                                && call.name.equals("bufferData")
                                && call.desc.equals("(ILjava/nio/ByteBuffer;I)V")) {
                            assertEquals(Opcodes.SIPUSH, call.getPrevious().getOpcode());
                            uploads++;
                        }
                    }
            assertEquals(1, uploads);
        }
    }

    @Test
    void rejectsUpstreamDrift() {
        var writer = new ClassWriter(0);
        writer.visit(Opcodes.V25, Opcodes.ACC_PUBLIC, CONVERTER.replace('.', '/'), null, "java/lang/Object", null);
        writer.visitEnd();
        assertThrows(IllegalStateException.class, () -> QuadIndexUploadPass.adapt(CONVERTER, writer.toByteArray()));
        byte[] unrelated = {0};
        assertSame(unrelated, QuadIndexUploadPass.adapt("zombie.core.opengl.VBORenderer", unrelated));
    }
}
