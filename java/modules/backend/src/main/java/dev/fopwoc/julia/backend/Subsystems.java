package dev.fopwoc.julia.backend;

import com.gtnewhorizons.angelica.sdlgpu.frame.ContextState;
import dev.fopwoc.julia.backend.diagnostic.DrawTrace;
import dev.fopwoc.julia.backend.framebuffer.BlendEquations;
import dev.fopwoc.julia.backend.framebuffer.ColorMasks;
import dev.fopwoc.julia.backend.framebuffer.Framebuffers;
import dev.fopwoc.julia.backend.query.Queries;
import dev.fopwoc.julia.backend.sdl.SdlBufferTextures;
import dev.fopwoc.julia.backend.sdl.SdlOcclusionQueries;
import dev.fopwoc.julia.backend.sdl.SdlTimestamps;
import dev.fopwoc.julia.backend.shader.LinkedPrograms;
import dev.fopwoc.julia.backend.shader.ShaderPrograms;
import dev.fopwoc.julia.backend.sync.GpuSyncs;
import dev.fopwoc.julia.backend.texture.TextureBindings;
import dev.fopwoc.julia.backend.texture.TextureObjects;
import dev.fopwoc.julia.backend.texture.TextureTable;
import dev.fopwoc.julia.backend.texture.TextureViews;
import dev.fopwoc.julia.backend.texture.VolumeUnpack;
import dev.fopwoc.julia.backend.texture.buffer.BufferTextures;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import java.util.WeakHashMap;

/**
 * Julia's own GL state and services for one hosted backend. The backend layers hold it directly; bytecode hooks and
 * other static entry points reach it through {@link #current()}, which also enforces the context-owning thread.
 */
public final class Subsystems implements AutoCloseable {
    private final ShaderPrograms shaderPrograms = new ShaderPrograms();
    private final LinkedPrograms linkedPrograms = new LinkedPrograms();
    private final Framebuffers framebuffers = new Framebuffers();
    private final ColorMasks colorMasks = new ColorMasks();
    private final BlendEquations blendEquations = new BlendEquations();
    private final DrawTrace drawTrace = new DrawTrace();
    private final TextureTable textures = new TextureTable();
    private final TextureBindings textureBindings = new TextureBindings();
    private final TextureObjects textureObjects = new TextureObjects(textures);
    private final TextureViews textureViews = new TextureViews(textures);
    private final BufferTextures bufferTextures =
            new BufferTextures(SdlBufferTextures::new, textureBindings, textures, linkedPrograms);
    private final WeakHashMap<ContextState, VolumeUnpack> volumeUnpack = new WeakHashMap<>();
    private final IntOpenHashSet bufferObjects = new IntOpenHashSet();
    private final Queries queries = new Queries(SdlTimestamps::new, SdlOcclusionQueries::new);
    private final GpuSyncs syncs = new GpuSyncs();

    public static Subsystems current() {
        return Upstream.ownedBackend().subsystems();
    }

    public ShaderPrograms shaderPrograms() {
        return shaderPrograms;
    }

    /** Julia's reflection of each linked upstream program. */
    public LinkedPrograms linkedPrograms() {
        return linkedPrograms;
    }

    public DrawTrace drawTrace() {
        return drawTrace;
    }

    public ColorMasks colorMasks() {
        return colorMasks;
    }

    public BlendEquations blendEquations() {
        return blendEquations;
    }

    public Framebuffers framebuffers() {
        return framebuffers;
    }

    /** Per-name texture records shared by the texture subsystems. */
    public TextureTable textures() {
        return textures;
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
    public IntSet bufferObjects() {
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
        drawTrace.close();
        queries.close();
        bufferTextures.close();
        linkedPrograms.clear();
        shaderPrograms.close();
        textureViews.close();
        textures.clear();
    }
}
