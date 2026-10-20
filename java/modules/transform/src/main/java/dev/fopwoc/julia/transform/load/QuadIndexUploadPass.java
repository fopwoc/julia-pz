package dev.fopwoc.julia.transform.load;

import static org.lwjgl.opengl.GL15.GL_STREAM_DRAW;

import dev.fopwoc.julia.transform.asm.Anchors;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;

/** Gives each converted quad draw its own index storage until its deferred GPU commands complete. */
public final class QuadIndexUploadPass implements UpstreamPatch {
    private static final String CONVERTER = "com.gtnewhorizons.angelica.glsm.QuadConverter";

    @Override
    public boolean targets(String name) {
        return name.equals(CONVERTER);
    }

    @Override
    public boolean apply(String name, ClassNode node) {
        int changed = 0;
        for (var method : node.methods) {
            if (!method.name.equals("uploadAndDraw")) continue;
            for (var instruction : method.instructions.toArray()) {
                if (!(instruction instanceof MethodInsnNode call)
                        || !call.owner.equals("com/gtnewhorizons/angelica/glsm/backend/RenderBackend")
                        || !call.name.equals("bufferSubData")
                        || !call.desc.equals("(IJLjava/nio/ByteBuffer;)V")) continue;
                var data = Anchors.previous(call);
                var offset = data == null ? null : Anchors.previous(data);
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
        Anchors.require("quad index upload", changed, 1);
        return true;
    }
}
