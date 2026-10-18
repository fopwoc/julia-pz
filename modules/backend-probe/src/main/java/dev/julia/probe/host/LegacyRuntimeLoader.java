package dev.julia.probe.host;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;

/** Keeps adapted graphics classes, bridge classes, and native bindings out of PZ's class loader. */
final class LegacyRuntimeLoader extends URLClassLoader {
    LegacyRuntimeLoader(URL[] libraries) { super(libraries, ClassLoader.getPlatformClassLoader()); }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        // Only the JDK-only dispatch API is shared with the host. Native bindings stay isolated.
        if (name.startsWith("dev.julia.runtime.")) return AdapterLauncher.class.getClassLoader().loadClass(name);
        return super.loadClass(name, resolve);
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        String resource = name.replace('.', '/') + ".class";
        URL location = findResource(resource);
        if (location == null) throw new ClassNotFoundException(name);
        try (var stream = location.openStream()) {
            byte[] bytes = stream.readAllBytes();
            if (name.equals("dev.julia.probe.adapter.AdapterDraw") || name.equals("dev.julia.probe.adapter.ResourceDraw")) bytes = SceneRedirector.adapt(bytes);
            bytes = LegacyBindings.adapt(name, bytes);
            bytes = WindowBindings.adapt(name, bytes);
            return defineClass(name, bytes, 0, bytes.length);
        } catch (IOException | RuntimeException exception) {
            throw new ClassNotFoundException(name, exception);
        }
    }
}
