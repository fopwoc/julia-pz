package dev.julia.zomboid;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.jar.JarFile;

/** ZombieBuddy entry point. Its isolated probe never shares LWJGL classes with the game. */
public final class Main {
    private static final AtomicBoolean STARTED = new AtomicBoolean();

    private Main() { }

    public static void main(String[] args) {
        if (!STARTED.compareAndSet(false, true)) return;
        try {
            Path archive = findArchive();
            Path output = outputDirectory().resolve("run-" + ProcessHandle.current().pid());
            Files.createDirectories(output);
            Files.writeString(output.resolve("status.txt"), "Julia loaded by ZombieBuddy; scheduling render-context probe\n");
            Runnable probe = () -> runProbe(archive, output);
            Class.forName("zombie.core.opengl.RenderThread")
                    .getMethod("queueInvokeOnRenderContext", Runnable.class).invoke(null, probe);
            System.out.println("[Julia] Loaded; Metal probe queued on the game's render context; output=" + output);
        } catch (Exception exception) {
            System.err.println("[Julia] Cannot start Metal probe");
            exception.printStackTrace();
        }
    }

    private static void runProbe(Path archive, Path output) {
        try {
            var urls = new ArrayList<URL>();
            String hash;
            try (var stream = Files.newInputStream(archive)) {
                var digest = MessageDigest.getInstance("SHA-256");
                stream.transferTo(new java.io.OutputStream() {
                    @Override public void write(int value) { digest.update((byte) value); }
                    @Override public void write(byte[] bytes, int offset, int length) { digest.update(bytes, offset, length); }
                });
                hash = HexFormat.of().formatHex(digest.digest());
            }
            Path libraries = output.resolve("runtime").resolve(hash);
            Files.createDirectories(libraries);
            try (var jar = new JarFile(archive.toFile())) {
                for (var entry : jar.stream().filter(entry -> entry.getName().startsWith("lib/")
                        && entry.getName().endsWith(".jar")).toList()) {
                    Path target = libraries.resolve(Path.of(entry.getName()).getFileName());
                    if (!Files.exists(target)) {
                        try (var input = jar.getInputStream(entry)) { Files.copy(input, target); }
                    }
                    urls.add(target.toUri().toURL());
                }
            }
            if (urls.isEmpty()) throw new IOException("Julia.jar contains no probe libraries");
            String mode = System.getProperty("julia.probe", "adapter");
            if (!mode.equals("adapter") && !mode.equals("window")) throw new IllegalArgumentException("Unknown Julia probe: " + mode);
            ClassLoader original = Thread.currentThread().getContextClassLoader();
            try (var hostGl = HostGlState.capture(Main.class.getClassLoader())) {
                try (var isolated = new URLClassLoader(urls.toArray(URL[]::new), ClassLoader.getPlatformClassLoader())) {
                    Thread.currentThread().setContextClassLoader(isolated);
                    try {
                        Class<?> launcher = Class.forName("dev.julia.probe.host.AdapterLauncher", true, isolated);
                        if (mode.equals("window")) {
                            long glfwWindow = (long) Class.forName("org.lwjglx.opengl.Display")
                                    .getMethod("getWindow").invoke(null);
                            long cocoaWindow = (long) Class.forName("org.lwjgl.glfw.GLFWNativeCocoa")
                                    .getMethod("glfwGetCocoaWindow", long.class).invoke(null, glfwWindow);
                            launcher.getMethod("runWindowSuite", Path.class, long.class).invoke(null, output, cocoaWindow);
                        } else {
                            launcher.getMethod("runSuite", Path.class).invoke(null, output);
                        }
                    } finally {
                        Thread.currentThread().setContextClassLoader(original);
                    }
                }
                hostGl.restoreAndVerify();
            }
            Files.writeString(output.resolve("host-gl-state.txt"), "RESTORED: host GL capabilities republished; native binding query matches the pre-probe value\n");
            Files.writeString(output.resolve("status.txt"), "PASS: " + mode
                    + " probe completed inside Project Zomboid; host GL dispatch restored; gameplay continuation unverified\n");
        } catch (Exception | LinkageError exception) {
            Throwable cause = exception instanceof InvocationTargetException invocation ? invocation.getCause() : exception;
            System.err.println("[Julia] Metal probe failed");
            cause.printStackTrace();
            try (var writer = new java.io.PrintWriter(Files.newBufferedWriter(output.resolve("failure.txt")))) {
                cause.printStackTrace(writer);
                Files.writeString(output.resolve("status.txt"), "FAIL: Julia probe; see failure.txt\n");
            } catch (IOException writeFailure) {
                writeFailure.printStackTrace();
            }
        }
    }

    private static Path findArchive() throws Exception {
        String explicit = System.getProperty("julia.modJar");
        if (explicit != null) return Path.of(explicit).toRealPath();
        var location = Main.class.getProtectionDomain().getCodeSource();
        if (location != null) {
            Path candidate = Path.of(location.getLocation().toURI());
            if (Files.isRegularFile(candidate) && candidate.getFileName().toString().equals("Julia.jar")) return candidate;
        }
        var filesystem = Class.forName("zombie.ZomboidFileSystem");
        Object instance = filesystem.getField("instance").get(null);
        String directory = (String) filesystem.getMethod("getModDir", String.class).invoke(instance, "Julia");
        for (String relative : new String[]{"media/java/client/Julia.jar", "42/media/java/client/Julia.jar"}) {
            Path candidate = Path.of(directory).resolve(relative);
            if (Files.isRegularFile(candidate)) return candidate.toRealPath();
        }
        throw new IOException("Cannot locate Julia.jar; supply -Djulia.modJar=/absolute/path/Julia.jar");
    }

    private static Path outputDirectory() throws Exception {
        String explicit = System.getProperty("julia.outputDir");
        if (explicit != null) return Path.of(explicit).toAbsolutePath();
        var filesystem = Class.forName("zombie.ZomboidFileSystem");
        Object instance = filesystem.getField("instance").get(null);
        return Path.of((String) filesystem.getMethod("getCacheDir").invoke(instance), "julia");
    }
}
