package dev.fopwoc.julia.backend;

import com.gtnewhorizons.angelica.sdlgpu.frame.ContextState;
import dev.fopwoc.julia.backend.framebuffer.Framebuffers;
import dev.fopwoc.julia.backend.platform.Platform;
import dev.fopwoc.julia.backend.query.Queries;
import dev.fopwoc.julia.backend.shader.ProgramResourceCache;
import dev.fopwoc.julia.backend.shader.ShaderPrograms;
import dev.fopwoc.julia.backend.sync.GpuSyncs;
import dev.fopwoc.julia.backend.texture.TextureBindings;
import dev.fopwoc.julia.backend.texture.TextureObjects;
import dev.fopwoc.julia.backend.texture.TextureViews;
import dev.fopwoc.julia.backend.texture.VolumeUnpack;
import dev.fopwoc.julia.backend.texture.buffer.BufferTextures;
import java.util.HashSet;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Julia's own GL state and services for one hosted backend. The backend layers hold it directly; bytecode hooks and
 * other static entry points reach it through {@link #current()}, which also enforces the context-owning thread.
 */
public final class Subsystems implements AutoCloseable {
    private final ShaderPrograms shaderPrograms = new ShaderPrograms();
    private final ProgramResourceCache programResources = new ProgramResourceCache();
    private final Framebuffers framebuffers = new Framebuffers();
    private final TextureBindings textureBindings = new TextureBindings();
    private final TextureObjects textureObjects = new TextureObjects();
    private final TextureViews textureViews = new TextureViews();
    private final BufferTextures bufferTextures = new BufferTextures(Platform.current()::createBufferTextureDriver);
    private final WeakHashMap<ContextState, VolumeUnpack> volumeUnpack = new WeakHashMap<>();
    private final Set<Integer> bufferObjects = new HashSet<>();
    private final Queries queries =
            new Queries(Platform.current()::createTimestampDriver, Platform.current()::createOcclusionDriver);
    private final GpuSyncs syncs;

    Subsystems(HostedBackend backend) {
        syncs = new GpuSyncs(backend);
    }

    public static Subsystems current() {
        return Upstream.ownedBackend().subsystems();
    }

    public ShaderPrograms shaderPrograms() {
        return shaderPrograms;
    }

    public ProgramResourceCache programResources() {
        return programResources;
    }

    public Framebuffers framebuffers() {
        return framebuffers;
    }

    public TextureBindings textureBindings() {
        return textureBindings;
    }

    public TextureObjects textureObjects() {
        return textureObjects;
    }

    public TextureViews textureViews() {
        return textureViews;
    }

    public BufferTextures bufferTextures() {
        return bufferTextures;
    }

    /** Pixel-unpack state GL keeps per context that Angelica does not track. */
    public VolumeUnpack volumeUnpack() {
        return volumeUnpack.computeIfAbsent(Upstream.context(), ignored -> new VolumeUnpack());
    }

    /** Buffer names that were bound or created, so {@code glIsBuffer} matches GL before storage exists. */
    public Set<Integer> bufferObjects() {
        return bufferObjects;
    }

    public Queries queries() {
        return queries;
    }

    public GpuSyncs syncs() {
        return syncs;
    }

    /** Native resources first, then shader bookkeeping; views last because they may still retain storage. */
    @Override
    public void close() {
        queries.close();
        bufferTextures.close();
        programResources.clear();
        shaderPrograms.close();
        textureViews.close();
    }
}
