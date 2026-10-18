package dev.julia.probe.adapter;

import com.gtnewhorizons.angelica.glsm.backend.BackendManager;
import com.gtnewhorizons.angelica.sdlgpu.SDLGPURenderBackend;
import com.gtnewhorizons.angelica.sdlgpu.frame.FrameManager;
import com.gtnewhorizons.angelica.sdlgpu.resource.FBOClearTracker;
import com.gtnewhorizons.angelica.sdlgpu.resource.ResourceManager;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

/** Version-pinned resource integration; private upstream state is resolved once and never duplicated. */
final class ResourceStateAccess {
    private static final VarHandle RESOURCES;
    private static final VarHandle FRAMES;
    private static final VarHandle CLEARS;
    static {
        try {
            var lookup = MethodHandles.privateLookupIn(SDLGPURenderBackend.class, MethodHandles.lookup());
            RESOURCES = lookup.findVarHandle(SDLGPURenderBackend.class, "resourceManager", ResourceManager.class);
            FRAMES = lookup.findVarHandle(SDLGPURenderBackend.class, "frameManager", FrameManager.class);
            CLEARS = lookup.findVarHandle(SDLGPURenderBackend.class, "fboClearTracker", FBOClearTracker.class);
        } catch (ReflectiveOperationException failure) { throw new ExceptionInInitializerError(failure); }
    }
    private ResourceStateAccess() { }
    private static HostedBackend backend() {
        ShaderStateAccess.context();
        if (!(BackendManager.RENDER_BACKEND instanceof HostedBackend backend)) throw new IllegalStateException("Expected hosted SDL backend");
        return backend;
    }
    static ResourceManager resources() { return (ResourceManager) RESOURCES.get(backend()); }
    static FrameManager frames() { return (FrameManager) FRAMES.get(backend()); }
    static FBOClearTracker clears() { return (FBOClearTracker) CLEARS.get(backend()); }
}
