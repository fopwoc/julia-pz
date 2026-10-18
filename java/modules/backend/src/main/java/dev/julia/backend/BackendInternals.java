package dev.julia.backend;

import com.gtnewhorizons.angelica.glsm.backend.BackendManager;
import com.gtnewhorizons.angelica.sdlgpu.SDLGPURenderBackend;
import com.gtnewhorizons.angelica.sdlgpu.frame.ContextState;
import com.gtnewhorizons.angelica.sdlgpu.frame.FrameManager;
import com.gtnewhorizons.angelica.sdlgpu.resource.FBOClearTracker;
import com.gtnewhorizons.angelica.sdlgpu.resource.ResourceManager;
import com.gtnewhorizons.angelica.sdlgpu.shader.ShaderManager;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.VarHandle;

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

    static {
        try {
            var lookup = MethodHandles.privateLookupIn(SDLGPURenderBackend.class, MethodHandles.lookup());
            SHADERS = lookup.findVarHandle(SDLGPURenderBackend.class, "shaderManager", ShaderManager.class);
            RESOURCES = lookup.findVarHandle(SDLGPURenderBackend.class, "resourceManager", ResourceManager.class);
            FRAMES = lookup.findVarHandle(SDLGPURenderBackend.class, "frameManager", FrameManager.class);
            CLEARS = lookup.findVarHandle(SDLGPURenderBackend.class, "fboClearTracker", FBOClearTracker.class);
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
