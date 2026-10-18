package dev.fopwoc.julia.runtime;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/** Binds the host callback through its public interface without linking either LWJGL runtime. */
public final class DebugCallback {
    public static final MethodType TYPE = MethodType.methodType(
            void.class, int.class, int.class, int.class, int.class, int.class, long.class, long.class);

    private DebugCallback() {}

    public static MethodHandle bind(Object callback, Class<?> contract) {
        if (callback == null) return null;
        if (!contract.isInterface() || !contract.isInstance(callback)) {
            throw new IllegalArgumentException("Expected a public debug callback interface");
        }
        try {
            return MethodHandles.publicLookup()
                    .findVirtual(contract, "invoke", TYPE)
                    .bindTo(callback);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalArgumentException("Incompatible debug callback interface", failure);
        }
    }
}
