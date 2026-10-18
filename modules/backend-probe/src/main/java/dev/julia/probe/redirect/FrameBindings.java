package dev.julia.probe.redirect;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.TryCatchBlockNode;

/** Exact version-checked PZ anchors; exceptions abort the active frame before escaping. */
public final class FrameBindings {
    private static final String HOOKS = "dev/julia/runtime/FrameHooks";
    public static final String RENDER_THREAD = "zombie/core/opengl/RenderThread";

    private FrameBindings() { }

    public static boolean adapt(ClassNode node) {
        if (!node.name.equals(RENDER_THREAD)) return false;
        var candidates = node.methods.stream().filter(m -> m.name.equals("lockStepRenderStep") && m.desc.equals("()Z")
                && (m.access & Opcodes.ACC_STATIC) != 0).toList();
        if (candidates.size() != 1) throw new IllegalArgumentException("Expected one static RenderThread.lockStepRenderStep()Z");
        var method = candidates.getFirst();
        MethodInsnNode draw = null;
        MethodInsnNode swap = null;
        boolean alreadyHooked = false;
        for (var instruction : method.instructions) {
            if (!(instruction instanceof MethodInsnNode call)) continue;
            if (call.owner.equals(HOOKS)) alreadyHooked = true;
            if (call.owner.equals("zombie/core/SpriteRenderer") && call.name.equals("postRender") && call.desc.equals("()V")) {
                if (draw != null || call.getOpcode() != Opcodes.INVOKEVIRTUAL) throw new IllegalArgumentException("Ambiguous sprite-render anchor");
                draw = call;
            }
            if (call.owner.equals("org/lwjglx/opengl/Display") && call.name.equals("update") && call.desc.equals("(Z)V")) {
                if (swap != null || draw == null || call.getOpcode() != Opcodes.INVOKESTATIC) throw new IllegalArgumentException("Ambiguous display-update anchor");
                swap = call;
            }
        }
        if (draw == null || swap == null || alreadyHooked) throw new IllegalArgumentException("Missing or previously transformed frame anchors");
        method.instructions.insertBefore(draw, new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, "begin", "()V", false));
        method.instructions.insertBefore(swap, new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, "present", "()V", false));
        var start = new LabelNode();
        var end = new LabelNode();
        var handler = new LabelNode();
        method.instructions.insert(start);
        var cleanup = new InsnList();
        cleanup.add(end);
        cleanup.add(handler);
        cleanup.add(new FrameNode(Opcodes.F_FULL, 0, null, 1, new Object[] {"java/lang/Throwable"}));
        cleanup.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, "abort", "(Ljava/lang/Throwable;)Ljava/lang/Throwable;", false));
        cleanup.add(new InsnNode(Opcodes.ATHROW));
        method.instructions.add(cleanup);
        method.tryCatchBlocks.add(new TryCatchBlockNode(start, end, handler, null));
        method.maxStack = Math.max(1, method.maxStack);
        return true;
    }
}
