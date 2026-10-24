package dev.fopwoc.julia.backend.texture;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Counts changes to texture storage: upstream handle and metadata writes, and Julia state that changes what a texture
 * samples. Per-draw caches remember the count they were filled at.
 *
 * <p>Process-wide rather than {@code Subsystems} state: upstream may change storage under its own lock from any
 * thread, where the context-owner check would fail.
 */
public final class TextureStorage {
    private static final AtomicLong GENERATION = new AtomicLong(1);

    private TextureStorage() {}

    /** Never 0, so a zeroed cache stamp is always stale. */
    public static long generation() {
        return GENERATION.get();
    }

    public static void changed() {
        GENERATION.incrementAndGet();
    }
}
