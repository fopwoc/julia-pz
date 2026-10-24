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
                    .findStatic(RenderDispatch.class, "resolve", MethodType.methodType(MethodHandle.class, Site.class));
        } catch (ReflectiveOperationException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    /**
     * One call site and the target its current session resolved. Every graphics call passes through here, so the
     * symbol is resolved once per session instead of being looked up per call.
     */
    static final class Site {
        /** One reference, so a reader never pairs one session with another session's target. */
        record Binding(RenderSession session, MethodHandle target) {}

        final String owner;
        final String name;
        final MethodType type;
        Binding binding;

        private Site(String owner, String name, MethodType type) {
            this.owner = owner;
            this.name = name;
            this.type = type;
        }

        @Override
        public String toString() {
            return owner + "." + name + type;
        }
    }

    private RenderDispatch() {}

    public static CallSite bootstrap(MethodHandles.Lookup caller, String name, MethodType type, String owner) {
        var resolver = RESOLVE.bindTo(new Site(owner, name, type));
        return new ConstantCallSite(MethodHandles.foldArguments(MethodHandles.exactInvoker(type), resolver));
    }

    private static MethodHandle resolve(Site site) throws ReflectiveOperationException {
        try {
            return RenderSession.current().target(site);
        } catch (Throwable failure) {
            throw FatalGraphics.failure("Cannot dispatch graphics operation: " + site, failure);
        }
    }
}
