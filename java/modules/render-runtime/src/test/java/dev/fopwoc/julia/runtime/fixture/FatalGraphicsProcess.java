package dev.fopwoc.julia.runtime.fixture;

import dev.fopwoc.julia.runtime.FatalGraphics;
import dev.fopwoc.julia.runtime.FrameHooks;
import dev.fopwoc.julia.runtime.RenderDispatch;
import dev.fopwoc.julia.runtime.RenderSession;
import dev.fopwoc.julia.runtime.UnsupportedGraphics;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.file.Files;
import java.nio.file.Path;

/** Disposable process: never arm the fatal policy in the test runner itself. */
public final class FatalGraphicsProcess {
    private FatalGraphicsProcess() {}

    public static void main(String[] arguments) throws Exception {
        Path output = Path.of(arguments[0]);
        String mode = arguments[1];
        FatalGraphics.arm(mode.equals("report-io") ? output.resolve("absent") : output);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> mark(output.resolve("shutdown-hook"))));
        try {
            if (mode.equals("unsupported") || mode.equals("report-io"))
                throw UnsupportedGraphics.failure("fixture/Renderer.draw: GL45.glUnsupported");
            var session = RenderSession.open(
                    (owner, name, type) -> {
                        if (mode.equals("resolve")) throw new NoSuchMethodException("fixture target missing");
                        return MethodHandles.lookup().findStatic(FatalGraphicsProcess.class, name, type);
                    },
                    new RenderSession.Frames() {
                        public void begin() {
                            if (mode.equals("begin")) throw new IllegalStateException("fixture begin failed");
                        }

                        public void present() {
                            if (mode.equals("present")) throw new IllegalStateException("fixture present failed");
                        }

                        public void abort() {
                            mark(output.resolve("cleanup"));
                        }

                        public void close() {}
                    });
            if (mode.equals("backend") || mode.equals("resolve")) {
                var type = MethodType.methodType(long.class, int.class, long.class);
                var site = RenderDispatch.bootstrap(MethodHandles.lookup(), "brokenDraw", type, "fixture/Backend");
                long ignored = (long) site.dynamicInvoker().invokeExact(1, 2L);
            } else if (mode.equals("shader")) {
                var site = RenderDispatch.bootstrap(
                        MethodHandles.lookup(), "brokenShader", MethodType.methodType(void.class), "fixture/Backend");
                site.dynamicInvoker().invokeExact();
            } else if (mode.equals("backend-void")) {
                var site = RenderDispatch.bootstrap(
                        MethodHandles.lookup(), "brokenVoid", MethodType.methodType(void.class), "fixture/Backend");
                site.dynamicInvoker().invokeExact();
            } else {
                FrameHooks.begin();
                if (mode.equals("abort")) FrameHooks.abort(new IllegalStateException("fixture overlay failed"));
                FrameHooks.present();
                session.close();
            }
        } catch (Throwable caughtByMod) {
            mark(output.resolve("caught"));
        }
        mark(output.resolve("continued"));
    }

    public static long brokenDraw(int first, long second) {
        throw new IllegalStateException("fixture backend exploded");
    }

    public static void brokenShader() {
        throw new UnsupportedOperationException(
                "SDL shader program translation failed: Too many fragment shader texture samplers (33, maximum 32)");
    }

    public static void brokenVoid() {
        throw new IllegalStateException("fixture void backend exploded");
    }

    private static void mark(Path path) {
        try {
            Files.writeString(path, "unexpected continuation");
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }
}
