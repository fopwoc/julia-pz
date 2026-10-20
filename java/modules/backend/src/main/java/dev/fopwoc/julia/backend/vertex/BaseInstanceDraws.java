package dev.fopwoc.julia.backend.vertex;

import com.gtnewhorizons.angelica.glsm.GLStateManager;
import com.gtnewhorizons.angelica.glsm.GLTypes;
import com.gtnewhorizons.angelica.sdlgpu.frame.ContextState;
import dev.fopwoc.julia.backend.Upstream;

/** GL base instance biases attribute records after division, while the shader instance ID stays zero-based. */
public final class BaseInstanceDraws {
    private static final ThreadLocal<Integer> BASE = ThreadLocal.withInitial(() -> 0);

    private BaseInstanceDraws() {}

    public static void glDrawArraysInstancedBaseInstance(int mode, int first, int count, int instances, int base) {
        Upstream.context();
        if (first < 0 || count < 0 || instances < 0)
            throw new IllegalArgumentException("Negative instanced draw range");
        if (count == 0 || instances == 0) return;
        int previous = BASE.get();
        BASE.set(base);
        try {
            GLStateManager.glDrawArraysInstanced(mode, first, count, instances);
        } finally {
            BASE.set(previous);
        }
    }

    /** Called after GLSM repairs legacy pointers, immediately before the existing instanced backend draw. */
    public static Offsets offsets(ContextState state) {
        long base = Integer.toUnsignedLong(BASE.get());
        if (base == 0) return Offsets.NONE;
        var vao = state.currentVao;
        int[] strides = new int[ContextState.MAX_VERTEX_ATTRIBS];
        int mask = 0;
        for (int attribute = 0; attribute < strides.length; attribute++) {
            if (!vao.attribEnabled[attribute]) continue;
            int binding = vao.attribBinding[attribute];
            if (vao.bindingDivisor[binding] == 0 || vao.bindingBuffer[binding] == 0) continue;
            int stride = vao.bindingStride[binding] != 0
                    ? vao.bindingStride[binding]
                    : vao.attribStride[attribute] != 0
                            ? vao.attribStride[attribute]
                            : vao.attribSize[attribute] * GLTypes.sizeBytes(vao.attribType[attribute]);
            if ((mask & 1 << binding) != 0 && strides[binding] != stride)
                throw new UnsupportedOperationException(
                        "Base-instance attributes sharing a binding need a common stride");
            mask |= 1 << binding;
            strides[binding] = stride;
        }
        long[] original = vao.bindingOffset.clone(), shifted = original.clone();
        for (int binding = 0; binding < strides.length; binding++) {
            if ((mask & 1 << binding) == 0) continue;
            long offset = Math.addExact(original[binding], Math.multiplyExact(base, strides[binding]));
            if (offset < 0 || offset > 0xffffffffL)
                throw new UnsupportedOperationException("Base-instance vertex offset exceeds SDL's 32-bit range");
            shifted[binding] = offset;
        }
        if (mask == 0) return Offsets.NONE;
        System.arraycopy(shifted, 0, vao.bindingOffset, 0, shifted.length);
        dirty(state, vao);
        return new Offsets(state, vao, original);
    }

    private static void dirty(ContextState state, ContextState.VAOState vao) {
        state.bumpAttribStateGen();
        state.pipeline.markInputDirty();
        vao.invalidateInputHash();
    }

    public static final class Offsets implements AutoCloseable {
        private static final Offsets NONE = new Offsets(null, null, null);
        private final ContextState state;
        private final ContextState.VAOState vao;
        private final long[] original;

        private Offsets(ContextState state, ContextState.VAOState vao, long[] original) {
            this.state = state;
            this.vao = vao;
            this.original = original;
        }

        @Override
        public void close() {
            if (state == null) return;
            System.arraycopy(original, 0, vao.bindingOffset, 0, original.length);
            dirty(state, vao);
        }
    }
}
