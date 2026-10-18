package dev.fopwoc.julia.backend;

import com.gtnewhorizons.angelica.glsm.GLStateManager;
import com.gtnewhorizons.angelica.glsm.backend.BackendManager;
import com.gtnewhorizons.angelica.sdlgpu.SDLGPURenderBackend;
import com.gtnewhorizons.angelica.sdlgpu.frame.ContextState;
import com.gtnewhorizons.angelica.sdlgpu.frame.FrameManager;
import com.gtnewhorizons.angelica.sdlgpu.resource.FBOClearTracker;
import com.gtnewhorizons.angelica.sdlgpu.resource.ResourceManager;
import com.gtnewhorizons.angelica.sdlgpu.shader.ShaderManager;
import dev.fopwoc.julia.backend.shader.ProgramResourceCache;
import dev.fopwoc.julia.backend.texture.TextureViews;
import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.VarHandle;
import java.util.HashSet;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Version-pinned access to private state of the published SDL backend (Angelica 2.2.34).
 *
 * <p>All private upstream integration is resolved here once; every accessor requires Julia's hosted backend and the
 * thread that owns the SDL context.
 */
public final class BackendInternals {
    private static final VarHandle SHADERS;
    private static final VarHandle RESOURCES;
    private static final VarHandle FRAMES;
    private static final VarHandle CLEARS;
    private static final MethodHandle CONTEXT;
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
