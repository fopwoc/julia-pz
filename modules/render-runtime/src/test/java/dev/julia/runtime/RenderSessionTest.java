package dev.julia.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class RenderSessionTest {
    private static final RenderSession.Targets NO_TARGETS = (owner, name, type) -> {
        throw new NoSuchMethodException(name);
    };

    @Test
    void balancesFramesAndRejectsInvalidTransitions() {
        var events = new ArrayList<String>();
        var session = RenderSession.open(NO_TARGETS, frames(events));
        assertThrows(IllegalStateException.class, FrameHooks::present);
        FrameHooks.begin();
        assertThrows(IllegalStateException.class, FrameHooks::begin);
        FrameHooks.present();
        FrameHooks.begin();
        session.close();
        session.close();
        assertEquals(java.util.List.of("begin", "present", "begin", "abort", "close"), events);
        assertThrows(IllegalStateException.class, FrameHooks::begin);
    }

    @Test
    void preservesOriginalFailureAndStillReleasesThreadAfterCleanupFails() {
        var events = new ArrayList<String>();
        var cleanup = new IllegalArgumentException("cleanup");
        var frames = new RenderSession.Frames() {
            public void begin() {
                events.add("begin");
            }

            public void present() {
                throw new IllegalStateException("present");
            }

            public void abort() {
                events.add("abort");
                throw cleanup;
            }

            public void close() {
                events.add("close");
            }
        };
        var session = RenderSession.open(NO_TARGETS, frames);
        FrameHooks.begin();
        var failure = assertThrows(IllegalStateException.class, FrameHooks::present);
        assertArrayEquals(new Throwable[] {cleanup}, failure.getSuppressed());
        assertThrows(IllegalStateException.class, FrameHooks::begin);
        session.close();
        assertEquals(java.util.List.of("begin", "abort", "close"), events);
        RenderSession.open(NO_TARGETS, frames(new ArrayList<>())).close();
    }

    @Test
    void rejectsCrossThreadCloseAndNestedBinding() throws InterruptedException {
        var session = RenderSession.open(NO_TARGETS, frames(new ArrayList<>()));
        try {
            assertThrows(IllegalStateException.class, () -> RenderSession.open(NO_TARGETS, frames(new ArrayList<>())));
            var failure = new AtomicReference<Throwable>();
            var thread = Thread.ofPlatform().start(() -> {
                try {
                    session.close();
                } catch (Throwable error) {
                    failure.set(error);
                }
            });
            thread.join();
            assertInstanceOf(IllegalStateException.class, failure.get());
            FrameHooks.begin();
            FrameHooks.present();
        } finally {
            session.close();
        }
    }

    @Test
    void callSiteResolvesPerSessionWithoutBoxingOrLeakingOldTarget() throws Throwable {
        var type = MethodType.methodType(long.class, int.class, long.class);
        var site = RenderDispatch.bootstrap(MethodHandles.lookup(), "draw", type, "test/Backend");
        var target = MethodHandles.lookup().findStatic(RenderSessionTest.class, "sum", type);
        var resolutions = new int[1];
        var session = RenderSession.open(
                (owner, name, actualType) -> {
                    resolutions[0]++;
                    return target;
                },
                frames(new ArrayList<>()));
        try {
            assertEquals(8L, (long) site.dynamicInvoker().invokeExact(3, 5L));
            assertEquals(10L, (long) site.dynamicInvoker().invokeExact(3, 7L));
            assertEquals(1, resolutions[0]);
        } finally {
            session.close();
        }
        assertThrows(IllegalStateException.class, () -> {
            long ignored = (long) site.dynamicInvoker().invokeExact(3, 7L);
        });
        var another = RenderSession.open(
                (owner, name, actualType) ->
                        MethodHandles.dropArguments(MethodHandles.constant(long.class, 99L), 0, int.class, long.class),
                frames(new ArrayList<>()));
        try {
            assertEquals(99L, (long) site.dynamicInvoker().invokeExact(3, 7L));
        } finally {
            another.close();
        }
    }

    @Test
    void doesNotAdaptMismatchedTargetTypesSilently() throws Throwable {
        var session = RenderSession.open(
                (owner, name, type) -> MethodHandles.constant(int.class, 1), frames(new ArrayList<>()));
        try {
            var site = RenderDispatch.bootstrap(
                    MethodHandles.lookup(), "draw", MethodType.methodType(long.class), "test/Backend");
            assertThrows(IllegalStateException.class, () -> {
                long ignored = (long) site.dynamicInvoker().invokeExact();
            });
        } finally {
            session.close();
        }
    }

    @Test
    void clearsBindingEvenWhenCloseAndAbortBothFail() {
        var session = RenderSession.open(NO_TARGETS, new RenderSession.Frames() {
            public void begin() {}

            public void present() {}

            public void abort() {
                throw new IllegalStateException("abort");
            }

            public void close() {
                throw new IllegalArgumentException("close");
            }
        });
        FrameHooks.begin();
        var error = assertThrows(IllegalStateException.class, session::close);
        assertEquals("close", error.getSuppressed()[0].getMessage());
        RenderSession.open(NO_TARGETS, frames(new ArrayList<>())).close();
    }

    static long sum(int first, long second) {
        return first + second;
    }

    private static RenderSession.Frames frames(ArrayList<String> events) {
        return new RenderSession.Frames() {
            public void begin() {
                events.add("begin");
            }

            public void present() {
                events.add("present");
            }

            public void abort() {
                events.add("abort");
            }

            public void close() {
                events.add("close");
            }
        };
    }
}
