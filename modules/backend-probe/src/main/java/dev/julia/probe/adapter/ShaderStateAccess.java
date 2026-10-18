package dev.julia.probe.adapter;

import com.gtnewhorizons.angelica.glsm.backend.BackendManager;
import com.gtnewhorizons.angelica.sdlgpu.SDLGPURenderBackend;
import com.gtnewhorizons.angelica.sdlgpu.frame.ContextState;
import com.gtnewhorizons.angelica.sdlgpu.shader.ShaderManager;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.VarHandle;

/** Version-pinned access to upstream tracked state; all private integration stays here. */
final class ShaderStateAccess {
    private static final VarHandle MANAGER;
    private static final MethodHandle CONTEXT;
    static {
        try {
            var lookup = MethodHandles.privateLookupIn(SDLGPURenderBackend.class, MethodHandles.lookup());
            MANAGER = lookup.findVarHandle(SDLGPURenderBackend.class, "shaderManager", ShaderManager.class);
            CONTEXT = lookup.findStatic(SDLGPURenderBackend.class, "s", MethodType.methodType(ContextState.class));
        } catch (ReflectiveOperationException failure) { throw new ExceptionInInitializerError(failure); }
    }
    private ShaderStateAccess() { }

    static UniformValue read(int program, int location) {
        var backend = BackendManager.RENDER_BACKEND;
        if (!(backend instanceof HostedBackend)) throw new IllegalStateException("Uniform queries require Julia's hosted SDL backend");
        var manager = (ShaderManager) MANAGER.get(backend);
        var object = manager.getProgram(program);
        if (object == null || !object.linked) throw new IllegalArgumentException("Unknown or unlinked program: " + program);
        ContextState context = context();
        if (object.externalUboBinding >= 0) throw new UnsupportedOperationException("External uniform-buffer state is not an ordinary uniform query");
        return UniformState.read(object, context.uniformStaging(object), location);
    }

    static ContextState context() {
        if (!(BackendManager.RENDER_BACKEND instanceof HostedBackend)) throw new IllegalStateException("Expected hosted SDL backend");
        ContextState context;
        try { context = (ContextState) CONTEXT.invokeExact(); }
        catch (RuntimeException | Error failure) { throw failure; }
        catch (Throwable failure) { throw new IllegalStateException("Cannot access SDL context state", failure); }
        if (context.owner != Thread.currentThread()) throw new IllegalStateException("Uniform query on another context thread");
        return context;
    }
}
