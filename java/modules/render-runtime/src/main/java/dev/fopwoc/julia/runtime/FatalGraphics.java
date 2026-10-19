package dev.fopwoc.julia.runtime;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Objects;

/** Live-agent failures terminate the JVM after writing a durable report; standalone library probes remain isolated. */
public final class FatalGraphics {
    private static final System.Logger LOG = System.getLogger(FatalGraphics.class.getName());
    private static final int EXIT_CODE = 70;
    private static final MethodHandle TARGET_FAILED;
    private static volatile Path report;

    static {
        try {
            TARGET_FAILED = MethodHandles.lookup()
                    .findStatic(
                            FatalGraphics.class,
                            "targetFailed",
                            MethodType.methodType(Object.class, String.class, Throwable.class));
        } catch (ReflectiveOperationException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    private FatalGraphics() {}

    /** Armed once by the live agent, before preparation or mod loading. There is no recovery mode. */
    public static synchronized void arm(Path output) {
        if (report != null) throw new IllegalStateException("Fatal graphics reporting is already armed");
        report = Objects.requireNonNull(output).resolve("fatal-error.txt").toAbsolutePath();
    }

    public static synchronized RuntimeException failure(String message, Throwable failure) {
        Path destination = report;
        if (destination != null) {
            try {
                var text = new StringWriter();
                var writer = new PrintWriter(text);
                writer.println("Julia fatal graphics failure");
                writer.println("time=" + Instant.now());
                writer.println("pid=" + ProcessHandle.current().pid());
                writer.println("thread=" + Thread.currentThread().getName());
                writer.println(message);
                failure.printStackTrace(writer);
                var bytes = ByteBuffer.wrap(text.toString().getBytes(StandardCharsets.UTF_8));
                try (var file =
                        FileChannel.open(destination, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                    while (bytes.hasRemaining()) file.write(bytes);
                    file.force(true);
                } catch (Throwable reportFailure) {
                    if (reportFailure != failure) failure.addSuppressed(reportFailure);
                }
                LOG.log(
                        System.Logger.Level.ERROR,
                        message + "; terminating JVM (exit " + EXIT_CODE + "); report=" + destination,
                        failure);
                System.err.flush();
            } finally {
                // Shutdown hooks and mod exception handlers must not continue using the broken renderer.
                Runtime.getRuntime().halt(EXIT_CODE);
            }
        }
        if (failure instanceof RuntimeException runtime) return runtime;
        if (failure instanceof Error error) throw error;
        return new IllegalStateException(message, failure);
    }

    static MethodHandle guard(MethodHandle target, String operation) {
        var handler = MethodHandles.insertArguments(TARGET_FAILED, 0, operation)
                .asType(MethodType.methodType(target.type().returnType(), Throwable.class));
        return MethodHandles.catchException(target, Throwable.class, handler);
    }

    private static Object targetFailed(String operation, Throwable failure) {
        throw failure("Graphics operation failed: " + operation, failure);
    }
}
