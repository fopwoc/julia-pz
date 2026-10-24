package dev.fopwoc.julia.backend.vertex;

import com.gtnewhorizons.angelica.glsm.GLStateManager;
import com.gtnewhorizons.angelica.glsm.GLTypes;
import com.gtnewhorizons.angelica.sdlgpu.frame.ContextState;
import dev.fopwoc.julia.backend.Upstream;

/** GL base instance biases attribute records after division, while the shader instance ID stays zero-based. */
public final class BaseInstanceDraws {
    // Render-thread state: every entry point checks the context owner first, so no thread-local is needed.
    private static int base;
    private static final int[] STRIDES = new int[ContextState.MAX_VERTEX_ATTRIBS];
    private static final Offsets SHIFTED = new Offsets();

    private BaseInstanceDraws() {}

    public static void glDrawArraysInstancedBaseInstance(int mode, int first, int count, int instances, int base) {
        Upstream.context();
        if (first < 0 || count < 0 || instances < 0)
            throw new IllegalArgumentException("Negative instanced draw range");
        if (count == 0 || instances == 0) return;
        int previous = BaseInstanceDraws.base;
        BaseInstanceDraws.base = base;
        try {
            GLStateManager.glDrawArraysInstanced(mode, first, count, instances);
        } finally {
            BaseInstanceDraws.base = previous;
        }
    }

    /** Called after GLSM repairs legacy pointers, immediately before the existing instanced backend draw. */
    public static Offsets offsets(ContextState state) {
        long base = Integer.toUnsignedLong(BaseInstanceDraws.base);
        if (base == 0) return Offsets.NONE;
        var vao = state.currentVao;
        int[] strides = STRIDES;
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
        if (mask == 0) return Offsets.NONE;
        long[] current = vao.bindingOffset;
        // Validate every shift before mutating, so a rejected draw leaves the VAO untouched.
        for (int binding = 0; binding < strides.length; binding++) {
            if ((mask & 1 << binding) == 0) continue;
            long offset = Math.addExact(current[binding], Math.multiplyExact(base, strides[binding]));
            if (offset < 0 || offset > 0xffffffffL)
                throw new UnsupportedOperationException("Base-instance vertex offset exceeds SDL's 32-bit range");
        }
        var offsets = SHIFTED.state == null ? SHIFTED : new Offsets();
        offsets.save(state, vao);
        for (int binding = 0; binding < strides.length; binding++)
            if ((mask & 1 << binding) != 0) current[binding] += base * strides[binding];
        dirty(state, vao);
        return offsets;
    }

    private static void dirty(ContextState state, ContextState.VAOState vao) {
        state.bumpAttribStateGen();
        state.pipeline.markInputDirty();
        vao.invalidateInputHash();
    }

    /** Restores the shifted VAO offsets; reused across draws, so it holds state only while a draw is open. */
    public static final class Offsets implements AutoCloseable {
        private static final Offsets NONE = new Offsets();
        private ContextState state;
        private ContextState.VAOState vao;
        private long[] original = new long[0];

        private void save(ContextState state, ContextState.VAOState vao) {
            if (original.length != vao.bindingOffset.length) original = new long[vao.bindingOffset.length];
            System.arraycopy(vao.bindingOffset, 0, original, 0, original.length);
            this.state = state;
            this.vao = vao;
        }

        @Override
        public void close() {
            if (state == null) return;
            System.arraycopy(original, 0, vao.bindingOffset, 0, original.length);
            dirty(state, vao);
            state = null;
            vao = null;
        }
    }
}
