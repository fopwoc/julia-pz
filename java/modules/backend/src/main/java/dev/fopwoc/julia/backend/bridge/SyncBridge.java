package dev.fopwoc.julia.backend.bridge;

import dev.fopwoc.julia.backend.BackendInternals;

/** OpenGL sync entry points backed by actual SDL/Metal completion fences. */
public final class SyncBridge {
    private SyncBridge() {}

    public static long glFenceSync(int condition, int flags) {
        return BackendInternals.syncs().create(condition, flags);
    }

    public static int glClientWaitSync(long sync, int flags, long timeout) {
        return BackendInternals.syncs().await(sync, flags, timeout);
    }

    public static void glDeleteSync(long sync) {
        BackendInternals.syncs().delete(sync);
    }

    public static boolean glIsSync(long sync) {
        return BackendInternals.syncs().contains(sync);
    }
}
