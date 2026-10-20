package dev.fopwoc.julia.backend;

import static org.lwjgl.opengl.GL11.*;

import com.gtnewhorizons.angelica.glsm.GLStateManager;
import com.gtnewhorizons.angelica.glsm.backend.BackendManager;
import com.gtnewhorizons.angelica.sdlgpu.SDLGPURenderBackend;
import com.gtnewhorizons.angelica.sdlgpu.frame.ContextState;
import com.gtnewhorizons.angelica.sdlgpu.frame.FrameManager;
import com.gtnewhorizons.angelica.sdlgpu.pipeline.AttachmentClear;
import com.gtnewhorizons.angelica.sdlgpu.pipeline.PipelineApplier;
import com.gtnewhorizons.angelica.sdlgpu.resource.FBOClearTracker;
import com.gtnewhorizons.angelica.sdlgpu.resource.PixelOps;
import com.gtnewhorizons.angelica.sdlgpu.resource.ResourceManager;
import com.gtnewhorizons.angelica.sdlgpu.shader.ShaderManager;
import dev.fopwoc.julia.backend.framebuffer.Framebuffers;
import dev.fopwoc.julia.backend.shader.ProgramResourceCache;
import dev.fopwoc.julia.backend.texture.TextureBindings;
import dev.fopwoc.julia.backend.texture.TextureObjects;
import dev.fopwoc.julia.backend.texture.TextureViews;
import dev.fopwoc.julia.backend.texture.VolumeUnpack;
import dev.fopwoc.julia.backend.texture.buffer.BufferTextures;
import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.VarHandle;
import java.util.HashSet;
import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Version-pinned access to private state of the published SDL backend (Angelica 2.2.34).
 *
 * <p>All private upstream integration is resolved here once; every accessor requires Julia's hosted backend and the
 * thread that owns the SDL context.
 */
public final class BackendInternals {
    private static final VarHandle PIPELINE_APPLIER;
    private static final VarHandle ATTACHMENT_CLEAR;
    private static final VarHandle SHADERS;
    private static final VarHandle RESOURCES;
    private static final VarHandle FRAMES;
    private static final VarHandle CLEARS;
    private static final MethodHandle CONTEXT;
    private static final MethodHandle RELEASE_SHADER_ATTACHMENT;
    private static final VarHandle UNSHRINKABLE;
    private static final MethodHandle DRAW_LOCK;
    private static final MethodHandle DRAW_UNLOCK;
    private static final VarHandle TEXTURE_HANDLES;
    private static final VarHandle TEXTURE_METAS;
    private static final VarHandle RESOURCE_LOCK;
    private static final MethodHandle FLUSH_TEXTURE_REFERENCES;
    private static final MethodHandle SUBMIT_PENDING_COMMANDS;

    static {
        try {
            var lookup = MethodHandles.privateLookupIn(SDLGPURenderBackend.class, MethodHandles.lookup());
            SUBMIT_PENDING_COMMANDS = lookup.findVirtual(
                    SDLGPURenderBackend.class, "flushAndSubmitMidFrame", MethodType.methodType(void.class));
            RELEASE_SHADER_ATTACHMENT = MethodHandles.privateLookupIn(ShaderManager.class, MethodHandles.lookup())
                    .findVirtual(
                            ShaderManager.class,
                            "releaseShaderAttachment",
                            MethodType.methodType(void.class, int.class));
            PIPELINE_APPLIER =
                    lookup.findVarHandle(SDLGPURenderBackend.class, "pipelineApplier", PipelineApplier.class);
            ATTACHMENT_CLEAR =
                    lookup.findVarHandle(SDLGPURenderBackend.class, "attachmentClear", AttachmentClear.class);
            SHADERS = lookup.findVarHandle(SDLGPURenderBackend.class, "shaderManager", ShaderManager.class);
            RESOURCES = lookup.findVarHandle(SDLGPURenderBackend.class, "resourceManager", ResourceManager.class);
            FRAMES = lookup.findVarHandle(SDLGPURenderBackend.class, "frameManager", FrameManager.class);
            CLEARS = lookup.findVarHandle(SDLGPURenderBackend.class, "fboClearTracker", FBOClearTracker.class);
            var glsmLookup = MethodHandles.privateLookupIn(GLStateManager.class, MethodHandles.lookup());
            UNSHRINKABLE =
                    glsmLookup.findStaticVarHandle(GLStateManager.class, "unshrinkableTextures", IntOpenHashSet.class);
            DRAW_LOCK = glsmLookup.findStatic(
                    GLStateManager.class, "acquireDrawLock", MethodType.methodType(boolean.class));
            DRAW_UNLOCK =
                    glsmLookup.findStatic(GLStateManager.class, "releaseDrawLock", MethodType.methodType(void.class));
            var resourceLookup = MethodHandles.privateLookupIn(ResourceManager.class, MethodHandles.lookup());
            TEXTURE_HANDLES =
                    resourceLookup.findVarHandle(ResourceManager.class, "textureHandles", Int2LongOpenHashMap.class);
            TEXTURE_METAS =
                    resourceLookup.findVarHandle(ResourceManager.class, "textureMetas", Int2ObjectOpenHashMap.class);
            RESOURCE_LOCK = resourceLookup.findVarHandle(
                    ResourceManager.class, "wLock", ReentrantReadWriteLock.WriteLock.class);
            FLUSH_TEXTURE_REFERENCES = resourceLookup.findVirtual(
                    ResourceManager.class, "beforeTextureReplaced", MethodType.methodType(void.class));
            CONTEXT = lookup.findStatic(SDLGPURenderBackend.class, "s", MethodType.methodType(ContextState.class));
        } catch (ReflectiveOperationException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    private BackendInternals() {}

    /** Initializes a just-linked program without binding it or releasing a pending-deletion current program. */
    public static void initializeUniforms(int program, Map<String, float[]> values) {
        var state = context();
        var target = shaders().getProgram(program);
        var previous = state.boundProgramObj;
        var applier = (PipelineApplier) PIPELINE_APPLIER.get(backend());
        try {
            // putUniform reads the program object only; encoder and logical bindings stay untouched.
            state.boundProgramObj = target;
            for (var entry : values.entrySet()) {
                int location = shaders().getUniformLocation(program, entry.getKey());
                if (location >= 0) applier.putUniform(state, location, entry.getValue());
            }
        } finally {
            state.boundProgramObj = previous;
        }
    }

    public static ContextState context() {
        backend();
        ContextState context;
        try {
            context = (ContextState) CONTEXT.invokeExact();
        } catch (RuntimeException | Error failure) {
            throw failure;
        } catch (Throwable failure) {
            throw new IllegalStateException("Cannot access SDL context state", failure);
        }
        if (context.owner != Thread.currentThread()) {
            throw new IllegalStateException("SDL context state accessed from another thread");
        }
        return context;
    }

    public static ShaderManager shaders() {
        return (ShaderManager) SHADERS.get(ownedBackend());
    }

    public static void detachShader(int program, int shader) {
        var manager = shaders();
        var object = manager.getProgram(program);
        if (object.vertexShader == shader) object.vertexShader = 0;
        else if (object.fragmentShader == shader) object.fragmentShader = 0;
        else if (object.geometryShader == shader) object.geometryShader = 0;
        else if (object.computeShader == shader) object.computeShader = 0;
        else throw new IllegalStateException("Internal shader is not attached to its program");
        try {
            RELEASE_SHADER_ATTACHMENT.invokeExact(manager, shader);
        } catch (RuntimeException | Error failure) {
            throw failure;
        } catch (Throwable failure) {
            throw new IllegalStateException("Cannot release internal shader attachment", failure);
        }
    }

    public static ResourceManager resources() {
        return (ResourceManager) RESOURCES.get(ownedBackend());
    }

    public static FrameManager frames() {
        return (FrameManager) FRAMES.get(ownedBackend());
    }

    public static FBOClearTracker clears() {
        return (FBOClearTracker) CLEARS.get(ownedBackend());
    }

    public static ProgramResourceCache programResources() {
        return ownedBackend().programResources();
    }

    public static BufferTextures bufferTextures() {
        return ownedBackend().bufferTextures();
    }

    public static Framebuffers framebuffers() {
        return ownedBackend().framebuffers();
    }

    /** Whole-texture deferred clears cannot identify slices; encode the existing masked shader clear immediately. */
    public static void clearFramebufferSlice(int mask) {
        var backend = ownedBackend();
        var state = context();
        var fbo = resources().getFbo(state.boundFboId);
        if (fbo.targetsDirty) fbo.recomputeTargets();
        framebuffers().bound(state.boundFboId);
        var clear = (AttachmentClear) ATTACHMENT_CLEAR.get(backend);
        var target = clear.forFbo(fbo);
        boolean color = (mask & GL_COLOR_BUFFER_BIT) != 0 && (state.pipeline.colorWriteMask & 15) != 0;
        boolean depth = (mask & GL_DEPTH_BUFFER_BIT) != 0 && state.pipeline.depthWriteEnabled;
        boolean stencil = (mask & GL_STENCIL_BUFFER_BIT) != 0 && (state.pipeline.stencilWriteMask & 255) != 0;
        int coverage = clear.coverage(state, target);
        if (coverage == AttachmentClear.EMPTY) return;
        color &= fbo.hasAnyColor;
        depth &= fbo.depthTexture != 0;
        stencil &= fbo.depthTexture != 0 && PixelOps.isDepthStencilFormat(fbo.depthFormat);
        if (!color && !depth && !stencil) return;
        ((PipelineApplier) PIPELINE_APPLIER.get(backend)).ensureRenderPass(state);
        var frame = frames().frame();
        if (frame.renderPass == 0 || !clear.clearInPass(state, frame, target, color, depth, stencil))
            throw new UnsupportedOperationException("Cannot clear framebuffer slice with current masks and formats");
    }

    public static TextureBindings textureBindings() {
        return ownedBackend().textureBindings();
    }

    public static TextureObjects textureObjects() {
        return ownedBackend().textureObjects();
    }

    public static VolumeUnpack volumeUnpack() {
        return ownedBackend().volumeUnpack();
    }

    public static TextureViews views() {
        return ownedBackend().textureViews();
    }

    public static void forgetTextureName(int name) {
        try {
            boolean locked = (boolean) DRAW_LOCK.invokeExact();
            try {
                ((IntOpenHashSet) UNSHRINKABLE.get()).remove(name);
            } finally {
                if (locked) DRAW_UNLOCK.invokeExact();
            }
        } catch (RuntimeException | Error failure) {
            throw failure;
        } catch (Throwable failure) {
            throw new IllegalStateException("Cannot clear texture name bookkeeping", failure);
        }
    }

    @SuppressWarnings("unchecked")
    public static void aliasTexture(int name, int original) {
        var resources = resources();
        var lock = (ReentrantReadWriteLock.WriteLock) RESOURCE_LOCK.get(resources);
        lock.lock();
        try {
            var handles = (Int2LongOpenHashMap) TEXTURE_HANDLES.get(resources);
            var metas = (Int2ObjectOpenHashMap<ResourceManager.TextureMeta>) TEXTURE_METAS.get(resources);
            if (handles.containsKey(name)) throw new IllegalArgumentException("Destination texture already allocated");
            handles.put(name, handles.get(original));
            metas.put(name, metas.get(original));
        } finally {
            lock.unlock();
        }
    }

    @SuppressWarnings("unchecked")
    public static void registerTextureView(int name, long handle, ResourceManager.TextureMeta meta) {
        var resources = resources();
        var lock = (ReentrantReadWriteLock.WriteLock) RESOURCE_LOCK.get(resources);
        lock.lock();
        try {
            var handles = (Int2LongOpenHashMap) TEXTURE_HANDLES.get(resources);
            if (handles.containsKey(name)) throw new IllegalArgumentException("Destination texture already allocated");
            handles.put(name, handle);
            ((Int2ObjectOpenHashMap<ResourceManager.TextureMeta>) TEXTURE_METAS.get(resources)).put(name, meta);
            resources.trackTextureHandle(handle);
        } finally {
            lock.unlock();
        }
    }

    public static boolean textureReferenced(ResourceManager resources, long handle) {
        var lock = (ReentrantReadWriteLock.WriteLock) RESOURCE_LOCK.get(resources);
        lock.lock();
        try {
            return ((Int2LongOpenHashMap) TEXTURE_HANDLES.get(resources)).containsValue(handle);
        } finally {
            lock.unlock();
        }
    }

    public static void deduplicateTextures() {
        var resources = resources();
        var lock = (ReentrantReadWriteLock.WriteLock) RESOURCE_LOCK.get(resources);
        lock.lock();
        try {
            var seen = new HashSet<Long>();
            var iterator = ((Int2LongOpenHashMap) TEXTURE_HANDLES.get(resources))
                    .int2LongEntrySet()
                    .iterator();
            while (iterator.hasNext()) if (!seen.add(iterator.next().getLongValue())) iterator.remove();
        } finally {
            lock.unlock();
        }
    }

    /** Submit queued uploads before the consuming commands, including when a diagnostic ends a frame early. */
    public static long timestampCommandBuffer() {
        ownedBackend().endComputeDispatchBatch();
        var state = context();
        var manager = frames();
        clears().materializeAllPendingClears(state);
        if (state.pendingSwapchainClear || state.pendingSwapchainDepthClear || state.pendingSwapchainStencilClear)
            manager.ensureFbo0RenderPass(manager.frame(), state);
        manager.endRenderPassIfActive(manager.frame());
        manager.endCopyPassIfActive();
        return manager.getCommandBuffer();
    }

    public static void submitPendingCommands() {
        try {
            SUBMIT_PENDING_COMMANDS.invokeExact((SDLGPURenderBackend) ownedBackend());
        } catch (RuntimeException | Error failure) {
            throw failure;
        } catch (Throwable failure) {
            throw new IllegalStateException("Cannot submit pending SDL commands", failure);
        }
    }

    public static void flushTextureReferences() {
        try {
            FLUSH_TEXTURE_REFERENCES.invokeExact(resources());
        } catch (RuntimeException | Error failure) {
            throw failure;
        } catch (Throwable failure) {
            throw new IllegalStateException("Cannot flush pending texture references", failure);
        }
    }

    private static HostedBackend ownedBackend() {
        context();
        return backend();
    }

    private static HostedBackend backend() {
        if (!(BackendManager.RENDER_BACKEND instanceof HostedBackend backend)) {
            throw new IllegalStateException("Expected Julia's hosted SDL backend");
        }
        return backend;
    }
}
