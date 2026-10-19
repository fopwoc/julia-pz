package dev.fopwoc.julia.runtime;

import java.lang.invoke.CallSite;
import java.lang.invoke.ConstantCallSite;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/** Typed invokedynamic dispatch; no LWJGL classes, argument boxing, or native state cross this boundary. */
public final class RenderDispatch {
    private static final MethodHandle RESOLVE;

    static {
        try {
            RESOLVE = MethodHandles.lookup()
                    .findStatic(
                            RenderDispatch.class,
                            "resolve",
                            MethodType.methodType(MethodHandle.class, String.class, String.class, MethodType.class));
        } catch (ReflectiveOperationException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    private RenderDispatch() {}

    public static CallSite bootstrap(MethodHandles.Lookup caller, String name, MethodType type, String owner) {
        var resolver = MethodHandles.insertArguments(RESOLVE, 0, owner, name, type);
        return new ConstantCallSite(MethodHandles.foldArguments(MethodHandles.exactInvoker(type), resolver));
    }

    private static MethodHandle resolve(String owner, String name, MethodType type)
            throws ReflectiveOperationException {
        try {
            return RenderSession.current().target(owner, name, type);
        } catch (Throwable failure) {
            throw FatalGraphics.failure("Cannot dispatch graphics operation: " + owner + "." + name + type, failure);
        }
    }
}
