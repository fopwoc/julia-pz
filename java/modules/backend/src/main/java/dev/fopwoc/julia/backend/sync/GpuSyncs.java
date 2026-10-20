package dev.fopwoc.julia.backend.sync;

import static org.lwjgl.opengl.GL32.*;

import com.gtnewhorizons.angelica.sdlgpu.SDLGPURenderBackend;
import dev.fopwoc.julia.backend.BackendInternals;
import java.util.HashSet;

/** Public GL fence lifetimes over the backend's reference-counted native SDL submission fences. */
public final class GpuSyncs {
    private final SDLGPURenderBackend backend;
    private final HashSet<Long> live = new HashSet<>();

    public GpuSyncs(SDLGPURenderBackend backend) {
        this.backend = backend;
    }

    public long create(int condition, int flags) {
        if (condition != GL_SYNC_GPU_COMMANDS_COMPLETE || flags != 0)
            throw new IllegalArgumentException("Fence requires GPU_COMMANDS_COMPLETE and zero flags");
        BackendInternals.timestampCommandBuffer();
        long sync = backend.fenceSync(condition, flags);
        // Submit at the GL fence boundary. Later commands cannot extend the fence, and deleting a
        // fence before a frame swap cannot resurrect an unresolved upstream fence token.
        BackendInternals.submitFenceCommands(sync);
        if (sync == 0 || !live.add(sync)) throw new IllegalStateException("Invalid native fence identity: " + sync);
        return sync;
    }

    public boolean contains(long sync) {
        return live.contains(sync);
    }

    public int await(long sync, int flags, long timeout) {
        require(sync);
        if ((flags & ~GL_SYNC_FLUSH_COMMANDS_BIT) != 0)
            throw new IllegalArgumentException("Invalid client wait flags: " + flags);
        // All public fences have already been submitted, so polling must not flush later work.
        return backend.clientWaitSync(sync, 0, timeout);
    }

    public void delete(long sync) {
        if (sync == 0) return;
        require(sync);
        live.remove(sync);
        backend.deleteSync(sync);
    }

    private void require(long sync) {
        if (!contains(sync)) throw new IllegalArgumentException("Unknown or deleted GPU sync: " + sync);
    }
}
