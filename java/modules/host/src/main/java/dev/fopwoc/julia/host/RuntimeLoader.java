package dev.fopwoc.julia.host;

import dev.fopwoc.julia.transform.load.SceneTransform;
import dev.fopwoc.julia.transform.load.UpstreamPatches;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;

/**
 * Defines graphics libraries, bridges, and native bindings apart from the game's class loader, adapting classes as
 * they load. Only the JDK-only {@code dev.fopwoc.julia.runtime} boundary is shared with the host.
 */
final class RuntimeLoader extends URLClassLoader {
    private static final System.Logger LOG = System.getLogger(RuntimeLoader.class.getName());
    private static final String SHARED = "dev.fopwoc.julia.runtime.";

    private final ClassLoader host;
    private final String scenePackage;

    static {
        registerAsParallelCapable();
    }

    RuntimeLoader(URL[] libraries, ClassLoader host, String scenePackage) {
        super(libraries, ClassLoader.getPlatformClassLoader());
        this.host = host;
        this.scenePackage = scenePackage;
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        if (name.startsWith(SHARED)) return host.loadClass(name);
        return super.loadClass(name, resolve);
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        URL location = findResource(name.replace('.', '/') + ".class");
        if (location == null) throw new ClassNotFoundException(name);
        try (var stream = location.openStream()) {
            byte[] bytes = stream.readAllBytes();
            if (!scenePackage.isEmpty() && name.startsWith(scenePackage)) {
                LOG.log(System.Logger.Level.DEBUG, "Redirecting scene {0}", name);
                bytes = SceneTransform.adapt(bytes);
            }
            bytes = UpstreamPatches.apply(name, bytes);
            return defineClass(name, bytes, 0, bytes.length);
        } catch (IOException | RuntimeException failure) {
            throw new ClassNotFoundException(name, failure);
        }
    }
}
