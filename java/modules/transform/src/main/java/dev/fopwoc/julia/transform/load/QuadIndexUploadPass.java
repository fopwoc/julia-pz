package dev.fopwoc.julia.transform.load;

import static org.lwjgl.opengl.GL15.GL_STREAM_DRAW;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;

/** Gives each converted quad draw its own index storage until its deferred GPU commands complete. */
public final class QuadIndexUploadPass {
    private static final String CONVERTER = "com.gtnewhorizons.angelica.glsm.QuadConverter";

    private QuadIndexUploadPass() {}

    public static byte[] adapt(String name, byte[] bytes) {
        if (!name.equals(CONVERTER)) return bytes;
        var node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        int changed = 0;
        for (var method : node.methods) {
            if (!method.name.equals("uploadAndDraw")) continue;
            for (var instruction : method.instructions.toArray()) {
                if (!(instruction instanceof MethodInsnNode call)
                        || !call.owner.equals("com/gtnewhorizons/angelica/glsm/backend/RenderBackend")
                        || !call.name.equals("bufferSubData")
                        || !call.desc.equals("(IJLjava/nio/ByteBuffer;)V")) continue;
                var data = call.getPrevious();
                while (data != null && data.getOpcode() == -1) data = data.getPrevious();
                var offset = data == null ? null : data.getPrevious();
                while (offset != null && offset.getOpcode() == -1) offset = offset.getPrevious();
                if (offset == null || offset.getOpcode() != Opcodes.LCONST_0)
                    throw new IllegalStateException("Unexpected quad index upload offset");
                // Deferred uploads run before the draw command buffer: overwriting the shared EBO
                // makes earlier draws consume the final quad's indices. Respecifying retains each store.
                method.instructions.remove(offset);
                method.instructions.insertBefore(call, new IntInsnNode(Opcodes.SIPUSH, GL_STREAM_DRAW));
                call.name = "bufferData";
                call.desc = "(ILjava/nio/ByteBuffer;I)V";
                changed++;
            }
        }
        if (changed != 1) throw new IllegalStateException("Expected one quad index upload, found " + changed);
        var writer = new ClassWriter(0);
        node.accept(writer);
        return writer.toByteArray();
    }
}
