package dev.fopwoc.julia.backend.buffer;

import dev.fopwoc.julia.backend.BackendInternals;
import java.nio.ByteBuffer;

/** Keeps uploaded element data available to quad conversion without downloading it from the GPU. */
public final class IndexBufferUploads {
    private IndexBufferUploads() {}

    public static void remember(int name, ByteBuffer data) {
        var resources = BackendInternals.resources();
        if (resources.getBufferHandle(name) == 0 || resources.getBufferSize(name) != data.remaining()) return;
        var shadow = resources.getOrAllocEboShadow(name, data.remaining());
        shadow.put(0, data, data.position(), data.remaining());
        resources.markEboShadowWanted(name);
        resources.bumpEboShadowVersion(name);
        resources.invalidateSplitCacheFor(name);
    }
}
