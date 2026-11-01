package dev.fopwoc.julia.transform.load;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.opengl.GL15.GL_STREAM_DRAW;

import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;

class QuadIndexUploadTest {
    private static final String CONVERTER = "com.gtnewhorizons.angelica.glsm.QuadConverter";

    @Test
    void sourceBuiltRendererRetainsEachScratchIndexUpload() throws IOException {
        try (var stream = getClass().getResourceAsStream("/" + CONVERTER.replace('.', '/') + ".class")) {
            assertNotNull(stream);
            var node = new ClassNode();
            new ClassReader(stream.readAllBytes()).accept(node, 0);
            int uploads = 0;
            for (var method : node.methods)
                for (var instruction : method.instructions)
                    if (instruction instanceof MethodInsnNode call) {
                        assertNotEquals("bufferSubData", call.name, "Shared scratch indices must not be overwritten");
                        if (method.name.equals("uploadAndDraw")
                                && call.name.equals("bufferData")
                                && call.desc.equals("(ILjava/nio/ByteBuffer;I)V")) {
                            assertEquals(GL_STREAM_DRAW, assertInstanceOf(LdcInsnNode.class, call.getPrevious()).cst);
                            uploads++;
                        }
                    }
            assertEquals(1, uploads);
        }
    }
}
