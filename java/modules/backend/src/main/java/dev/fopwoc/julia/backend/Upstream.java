package dev.fopwoc.julia.backend;

import static org.lwjgl.opengl.GL11.*;

import com.gtnewhorizons.angelica.glsm.GLStateManager;
import com.gtnewhorizons.angelica.glsm.backend.BackendManager;
import com.gtnewhorizons.angelica.sdlgpu.SDLGPURenderBackend;
import com.gtnewhorizons.angelica.sdlgpu.frame.ContextState;
import com.gtnewhorizons.angelica.sdlgpu.frame.FenceTracker;
import com.gtnewhorizons.angelica.sdlgpu.frame.FrameManager;
import com.gtnewhorizons.angelica.sdlgpu.pipeline.AttachmentClear;
import com.gtnewhorizons.angelica.sdlgpu.pipeline.DrawDispatch;
import com.gtnewhorizons.angelica.sdlgpu.pipeline.PipelineApplier;
import com.gtnewhorizons.angelica.sdlgpu.resource.FBOClearTracker;
import com.gtnewhorizons.angelica.sdlgpu.resource.ResourceManager;
import com.gtnewhorizons.angelica.sdlgpu.shader.ShaderManager;
import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.VarHandle;
import java.util.HashSet;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Version-pinned access to private state of the published SDL backend (Angelica 2.2.34). Julia's own state lives in
 * {@link Subsystems}.
 *
 * <p>All private upstream integration is resolved here once; every accessor requires Julia's hosted backend and the
 * thread that owns the SDL context.
 */
public final class Upstream {
    private static final VarHandle PIPELINE_APPLIER;
    private static final VarHandle DRAW_DISPATCH;
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
    private static final MethodHandle SUBMIT_FENCE_COMMANDS;
    private static final VarHandle FENCE_TRACKER;
    private static final VarHandle FENCE_MAP;

    static {
        try {
            var lookup = MethodHandles.privateLookupIn(SDLGPURenderBackend.class, MethodHandles.lookup());
            SUBMIT_PENDING_COMMANDS = lookup.findVirtual(
                    SDLGPURenderBackend.class, "flushAndSubmitMidFrame", MethodType.methodType(void.class));
            FENCE_TRACKER = lookup.findVarHandle(SDLGPURenderBackend.class, "fenceTracker", FenceTracker.class);
            FENCE_MAP = MethodHandles.privateLookupIn(FenceTracker.class, MethodHandles.lookup())
                    .findVarHandle(FenceTracker.class, "fenceMap", Long2LongOpenHashMap.class);
            SUBMIT_FENCE_COMMANDS = lookup.findVirtual(
                    SDLGPURenderBackend.class, "midFrameFenceFlush", MethodType.methodType(void.class));
            RELEASE_SHADER_ATTACHMENT = MethodHandles.privateLookupIn(ShaderManager.class, MethodHandles.lookup())
                    .findVirtual(
                            ShaderManager.class,
                            "releaseShaderAttachment",
                            MethodType.methodType(void.class, int.class));
            DRAW_DISPATCH = lookup.findVarHandle(SDLGPURenderBackend.class, "drawDispatch", DrawDispatch.class);
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

    private Upstream() {}

    public static PipelineApplier pipeline() {
        return (PipelineApplier) PIPELINE_APPLIER.get(ownedBackend());
    }

    public static DrawDispatch drawDispatch() {
        return (DrawDispatch) DRAW_DISPATCH.get(ownedBackend());
    }

    /** The pinned readback path selects its object from the execution mirror, not the logical target bank. */
    public static void withReadbackTexture(int texture, Runnable operation) {
        var state = context();
        int unit = state.activeTextureUnit;
        int previous = state.boundTextures[unit];
        try {
            state.boundTextures[unit] = texture;
            operation.run();
        } finally {
            state.boundTextures[unit] = previous;
        }
    }

    public static void lockedDraw(Runnable action) {
        ownedBackend();
        try {
            boolean locked = (boolean) DRAW_LOCK.invokeExact();
            try {
                action.run();
            } finally {
                if (locked) DRAW_UNLOCK.invokeExact();
            }
        } catch (RuntimeException | Error failure) {
            throw failure;
        } catch (Throwable failure) {
            throw new IllegalStateException("Cannot acquire graphics draw lock", failure);
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

    public static AttachmentClear attachmentClear() {
        return (AttachmentClear) ATTACHMENT_CLEAR.get(ownedBackend());
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

    /**
     * Ends any open render or copy pass, after flushing queued compute and clears, and returns the command buffer the
     * next pass will record into. Timestamps and occlusion query boundaries must sit between passes.
     */
    public static long commandBufferOutsidePasses() {
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

    public static void submitFenceCommands(long sync) {
        try {
            var backend = ownedBackend();
            SUBMIT_FENCE_COMMANDS.invokeExact((SDLGPURenderBackend) backend);
            var fences = (Long2LongOpenHashMap) FENCE_MAP.get((FenceTracker) FENCE_TRACKER.get(backend));
            if (fences.get(sync) == 0) throw new IllegalStateException("SDL submission did not acquire a GPU fence");
        } catch (RuntimeException | Error failure) {
            throw failure;
        } catch (Throwable failure) {
            throw new IllegalStateException("Cannot submit SDL fence commands", failure);
        }
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

    static HostedBackend ownedBackend() {
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
