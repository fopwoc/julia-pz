package dev.fopwoc.julia.transform.load;

import dev.fopwoc.julia.transform.Endpoint;
import dev.fopwoc.julia.transform.asm.Anchors;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/** Captures logical graphics state at the common draw boundary, including indirect and instanced calls. */
public final class DrawTracePass implements UpstreamPatch {
    private static final String STATE = "com/gtnewhorizons/angelica/sdlgpu/frame/ContextState";

    @Override
    public boolean targets(String name) {
        return name.equals("com.gtnewhorizons.angelica.sdlgpu.pipeline.PipelineApplier");
    }

    @Override
    public boolean apply(String name, ClassNode node) {
        int edits = 0;
        for (var method : node.methods)
            if (method.name.equals("ensureDrawRenderPass")
                    && method.desc.equals(
                            "(L" + STATE + ";Lcom/gtnewhorizons/angelica/sdlgpu/frame/FrameManager$FrameState;)V")) {
                var head = new InsnList();
                head.add(new VarInsnNode(Opcodes.ALOAD, 1));
                head.add(new MethodInsnNode(
                        Opcodes.INVOKESTATIC, Endpoint.DRAW_TRACE.owner(), "beforeDraw", "(L" + STATE + ";)V", false));
                method.instructions.insert(head);
                edits++;
            }
        Anchors.require("draw-trace boundary", edits, 1);
        return true;
    }
}
