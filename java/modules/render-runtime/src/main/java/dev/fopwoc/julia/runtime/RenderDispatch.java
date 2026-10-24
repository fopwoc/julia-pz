package dev.fopwoc.julia.runtime;

import java.lang.invoke.CallSite;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.MutableCallSite;

/** Typed invokedynamic dispatch; no LWJGL classes, argument boxing, or native state cross this boundary. */
public final class RenderDispatch {
    private static final MethodHandle RESOLVE, OWNS, BEFORE_CALL;

    static {
        try {
            var lookup = MethodHandles.lookup();
            RESOLVE = lookup.findStatic(
                    RenderDispatch.class, "resolve", MethodType.methodType(MethodHandle.class, Site.class));
            OWNS = lookup.findStatic(
                    RenderDispatch.class, "owns", MethodType.methodType(boolean.class, RenderSession.class));
            BEFORE_CALL = lookup.findStatic(
                    RenderDispatch.class,
                    "beforeCall",
                    MethodType.methodType(void.class, RenderSession.class, Site.class));
        } catch (ReflectiveOperationException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    /**
     * One call site. Once a session resolves it, the site links that session's target directly so the JIT can inline
     * it; a guard on session ownership falls back to resolution for any other thread or after the session closes.
     */
    static final class Site {
        final String owner;
        final String name;
        final MethodType type;
        private final MutableCallSite callSite;
        private final MethodHandle unbound;

        private Site(String owner, String name, MethodType type) {
            this.owner = owner;
            this.name = name;
            this.type = type;
            callSite = new MutableCallSite(type);
            unbound = MethodHandles.foldArguments(MethodHandles.exactInvoker(type), RESOLVE.bindTo(this));
            callSite.setTarget(unbound);
        }

        /** Links {@code target} for calls on {@code session}'s thread while the session stays usable. */
        void bind(RenderSession session, MethodHandle target) {
            var parameters = type.parameterList();
            var entered =
                    MethodHandles.foldArguments(target, MethodHandles.insertArguments(BEFORE_CALL, 0, session, this));
            var owned = MethodHandles.dropArguments(OWNS.bindTo(session), 0, parameters);
            callSite.setTarget(MethodHandles.guardWithTest(owned, entered, unbound));
        }

        /** Drops a closed session's target so its runtime class loader can unload. */
        void unbind() {
            callSite.setTarget(unbound);
        }

        @Override
        public String toString() {
            return owner + "." + name + type;
        }
    }

    private RenderDispatch() {}

    public static CallSite bootstrap(MethodHandles.Lookup caller, String name, MethodType type, String owner) {
        return new Site(owner, name, type).callSite;
    }

    private static MethodHandle resolve(Site site) throws ReflectiveOperationException {
        try {
            return RenderSession.current().target(site);
        } catch (Throwable failure) {
            throw FatalGraphics.failure("Cannot dispatch graphics operation: " + site, failure);
        }
    }

    private static boolean owns(RenderSession session) {
        return session.ownedHere();
    }

    private static void beforeCall(RenderSession session, Site site) {
        try {
            session.beforeCall();
        } catch (Throwable failure) {
            throw FatalGraphics.failure("Cannot dispatch graphics operation: " + site, failure);
        }
    }
}
