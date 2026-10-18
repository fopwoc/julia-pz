package dev.julia.host;

import dev.julia.runtime.IsolatedTargets;
import dev.julia.runtime.RenderSession;
import dev.julia.transform.Endpoint;
import java.io.File;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Runs an entry point inside a fresh isolated graphics runtime on the calling thread.
 *
 * <p>The runtime gets its own adapting class loader over this module's libraries, a typed {@link RenderSession} bound
 * to every {@link Endpoint}, and SDL's presenter thread disabled. All of it is torn down before {@link #invoke}
 * returns.
 *
 * @param scenePackage package prefix whose classes are fully redirected at load time; empty for none
 * @param frames frame callbacks for transformed game call sites
 */
public record IsolatedRuntime(String scenePackage, RenderSession.Frames frames) {
    private static final System.Logger LOG = System.getLogger(IsolatedRuntime.class.getName());
    private static final String PRESENTER_PROPERTY = "angelica.sdlgpu.disablePresenterThread";

    public IsolatedRuntime {
        Objects.requireNonNull(scenePackage);
        Objects.requireNonNull(frames);
    }

    /** Invokes {@code entryClass.method} as a public static method of {@code type}; arguments must be JDK types. */
    public void invoke(String entryClass, String method, MethodType type, Object... arguments) throws Exception {
        LOG.log(System.Logger.Level.INFO, "Starting isolated graphics runtime for {0}.{1}", entryClass, method);
        ClassLoader context = Thread.currentThread().getContextClassLoader();
        String presenter = System.getProperty(PRESENTER_PROPERTY);
        var host = IsolatedRuntime.class.getClassLoader();
        try (var loader = new RuntimeLoader(libraries(host), host, scenePackage);
                var _ = RenderSession.open(new IsolatedTargets(loader, Endpoint.owners()), frames)) {
            System.setProperty(PRESENTER_PROPERTY, "true");
            Thread.currentThread().setContextClassLoader(loader);
            var entry = Class.forName(entryClass, true, loader);
            var target = MethodHandles.publicLookup().findStatic(entry, method, type);
            try {
                target.invokeWithArguments(arguments);
            } catch (Exception | Error failure) {
                throw failure;
            } catch (Throwable failure) {
                throw new IllegalStateException(failure);
            }
        } finally {
            Thread.currentThread().setContextClassLoader(context);
            if (presenter == null) System.clearProperty(PRESENTER_PROPERTY);
            else System.setProperty(PRESENTER_PROPERTY, presenter);
        }
        LOG.log(System.Logger.Level.INFO, "Isolated graphics runtime closed");
    }

    /** The runtime reuses the libraries this module was loaded from: a nested-JAR loader or the JVM class path. */
    private static URL[] libraries(ClassLoader host) {
        if (host instanceof URLClassLoader urls) return urls.getURLs();
        return Arrays.stream(System.getProperty("java.class.path").split(Pattern.quote(File.pathSeparator)))
                .map(entry -> {
                    try {
                        return Path.of(entry).toUri().toURL();
                    } catch (MalformedURLException failure) {
                        throw new IllegalArgumentException(failure);
                    }
                })
                .toArray(URL[]::new);
    }
}
