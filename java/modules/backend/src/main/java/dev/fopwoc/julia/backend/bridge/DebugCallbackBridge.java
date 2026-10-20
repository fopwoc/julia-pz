package dev.fopwoc.julia.backend.bridge;

import com.gtnewhorizons.angelica.glsm.GLStateManager;
import dev.fopwoc.julia.backend.Upstream;
import dev.fopwoc.julia.runtime.DebugCallback;
import java.lang.invoke.MethodHandle;

/** Registers a host-owned Java callback in the SDL validation relay, without allocating a native closure. */
public final class DebugCallbackBridge {
    private DebugCallbackBridge() {}

    public static void glDebugMessageCallback(MethodHandle callback, long userParam) {
        Upstream.context();
        if (callback != null && !callback.type().equals(DebugCallback.TYPE)) {
            throw new IllegalArgumentException("Invalid debug callback signature: " + callback.type());
        }
        GLStateManager.registerDebugMessageListener(
                callback == null
                        ? null
                        : (source, type, id, severity, length, message, parameter) -> {
                            try {
                                callback.invokeExact(source, type, id, severity, length, message, userParam);
                            } catch (RuntimeException | Error failure) {
                                throw failure;
                            } catch (Throwable failure) {
                                throw new IllegalStateException("Debug callback failed", failure);
                            }
                        },
                userParam);
    }
}
