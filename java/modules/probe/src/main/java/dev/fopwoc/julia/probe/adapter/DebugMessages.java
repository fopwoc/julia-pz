package dev.fopwoc.julia.probe.adapter;

import static org.lwjgl.sdl.SDLLog.SDL_LOG_PRIORITY_ERROR;

import com.gtnewhorizons.angelica.sdlgpu.util.DebugMessageRelay;
import dev.fopwoc.julia.backend.bridge.DebugCallbackBridge;
import dev.fopwoc.julia.runtime.DebugCallback;
import java.lang.invoke.MethodHandles;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

/** Injects a validation-relay message without relying on a driver error or an enabled validation layer. */
public final class DebugMessages {
    private static int retainedCalls;

    private DebugMessages() {}

    public static void armShutdownCheck() throws ReflectiveOperationException {
        retainedCalls = 0;
        var callback = MethodHandles.lookup().findStatic(DebugMessages.class, "retained", DebugCallback.TYPE);
        DebugCallbackBridge.glDebugMessageCallback(callback, 0L);
        emit("before shutdown");
        if (retainedCalls != 1) throw new IllegalStateException("Shutdown-check callback was not registered");
    }

    public static void verifyShutdown() {
        emit("after shutdown");
        if (retainedCalls != 1) throw new IllegalStateException("Shutdown retained the host debug callback");
    }

    private static void retained(int source, int type, int id, int severity, int length, long message, long parameter) {
        retainedCalls++;
    }

    public static void emit(String text) {
        try (var stack = MemoryStack.stackPush()) {
            DebugMessageRelay.onSdlMessage(SDL_LOG_PRIORITY_ERROR, MemoryUtil.memAddress(stack.UTF8(text)));
        }
    }
}
