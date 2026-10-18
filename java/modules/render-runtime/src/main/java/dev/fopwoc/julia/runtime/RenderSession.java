package dev.fopwoc.julia.runtime;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import java.util.HashMap;
import java.util.Objects;

/** Thread-owned boundary between game call sites and an isolated graphics runtime. */
public final class RenderSession implements AutoCloseable {
    @FunctionalInterface
    public interface Targets {
        MethodHandle resolve(String owner, String name, MethodType type) throws ReflectiveOperationException;
    }

    public interface Frames {
        void begin();

        void present();

        void abort();

        void close();
    }

    private enum State {
        READY,
        FRAME,
        FAILED,
        CLOSED
    }

    private record Symbol(String owner, String name, MethodType type) {}

    private static final ThreadLocal<RenderSession> CURRENT = new ThreadLocal<>();
    private final Thread owner = Thread.currentThread();
    private final Targets targets;
    private final Frames frames;
    private final HashMap<Symbol, MethodHandle> methods = new HashMap<>();
    private State state = State.READY;

    private RenderSession(Targets targets, Frames frames) {
        this.targets = Objects.requireNonNull(targets);
        this.frames = Objects.requireNonNull(frames);
    }

    public static RenderSession open(Targets targets, Frames frames) {
        if (CURRENT.get() != null) throw new IllegalStateException("A render session is already bound to this thread");
        var session = new RenderSession(targets, frames);
        CURRENT.set(session);
        return session;
    }

    static RenderSession current() {
        var session = CURRENT.get();
        if (session == null)
            throw new IllegalStateException(
                    "No render session on thread " + Thread.currentThread().getName());
        session.requireUsable();
        return session;
    }

    MethodHandle target(String owner, String name, MethodType type) throws ReflectiveOperationException {
        requireUsable();
        var symbol = new Symbol(owner, name, type);
        var method = methods.get(symbol);
        if (method == null) {
            method = targets.resolve(owner, name, type);
            if (!method.type().equals(type)) throw new IllegalStateException("Render target type mismatch: " + symbol);
            methods.put(symbol, method);
        }
        return method;
    }

    void begin() {
        requireUsable();
        if (state != State.READY) throw new IllegalStateException("Frame already open");
        state = State.FRAME;
        try {
            frames.begin();
        } catch (RuntimeException | Error failure) {
            fail(failure);
            throw failure;
        }
    }

    void present() {
        requireUsable();
        if (state != State.FRAME) throw new IllegalStateException("No frame to present");
        try {
            frames.present();
            state = State.READY;
        } catch (RuntimeException | Error failure) {
            fail(failure);
            throw failure;
        }
    }

    private void fail(Throwable failure) {
        boolean frameOpen = state == State.FRAME;
        state = State.FAILED;
        if (frameOpen) {
            try {
                frames.abort();
            } catch (RuntimeException | Error cleanup) {
                if (cleanup != failure) failure.addSuppressed(cleanup);
            }
        }
    }

    static Throwable abort(Throwable failure) {
        var session = CURRENT.get();
        if (session != null && session.state == State.FRAME) session.fail(failure);
        return failure;
    }

    private void requireOwner() {
        if (Thread.currentThread() != owner) throw new IllegalStateException("Render session used from another thread");
    }

    private void requireUsable() {
        requireOwner();
        if (state == State.CLOSED || state == State.FAILED)
            throw new IllegalStateException("Render session is " + state);
    }

    @Override
    public void close() {
        requireOwner();
        if (state == State.CLOSED) return;
        Throwable failure = null;
        try {
            if (state == State.FRAME) {
                try {
                    frames.abort();
                } catch (RuntimeException | Error cleanup) {
                    failure = cleanup;
                }
            }
            try {
                frames.close();
            } catch (RuntimeException | Error cleanup) {
                if (failure == null) failure = cleanup;
                else if (failure != cleanup) failure.addSuppressed(cleanup);
            }
        } finally {
            state = State.CLOSED;
            methods.clear();
            CURRENT.remove();
        }
        if (failure instanceof RuntimeException runtime) throw runtime;
        if (failure instanceof Error error) throw error;
    }
}
