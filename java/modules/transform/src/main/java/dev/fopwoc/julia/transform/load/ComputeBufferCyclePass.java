package dev.fopwoc.julia.transform.load;

import dev.fopwoc.julia.transform.asm.Anchors;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;

/** Keeps the contents of read-write storage buffers across compute passes. */
public final class ComputeBufferCyclePass implements UpstreamPatch {
    private static final String BINDER = "com.gtnewhorizons.angelica.sdlgpu.compute.ComputeBinder";

    @Override
    public boolean targets(String name) {
        return name.equals(BINDER);
    }

    @Override
    public boolean apply(String name, ClassNode node) {
        int changed = 0;
        for (var method : node.methods)
            for (var instruction : method.instructions.toArray()) {
                if (!(instruction instanceof MethodInsnNode call)
                        || !call.owner.equals("org/lwjgl/sdl/SDL_GPUStorageBufferReadWriteBinding")
                        || !call.name.equals("cycle")
                        || !call.desc.equals("(Z)Lorg/lwjgl/sdl/SDL_GPUStorageBufferReadWriteBinding;")) continue;
                // SDL cycling swaps in a fresh backing buffer whenever the frame already references this one, so
                // per-chunk dispatches that accumulate atomic counters and records each land in a different buffer.
                // GL compute never discards storage contents; SDL orders the passes with barriers instead.
                method.instructions.insertBefore(call, new InsnNode(Opcodes.POP));
                method.instructions.insertBefore(call, new InsnNode(Opcodes.ICONST_0));
                changed++;
            }
        Anchors.require("compute storage buffer cycle", changed, 1);
        return true;
    }
}
