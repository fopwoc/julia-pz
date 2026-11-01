package dev.fopwoc.julia.probe.imgui;

import dev.fopwoc.julia.transform.prepare.RuntimePreparation;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;

/** Reads only ImGui classes from an external archive; never loads the game's entry points or window system. */
final class ImGuiArchiveLoader extends URLClassLoader {
    private final RuntimePreparation preparation = new RuntimePreparation();

    ImGuiArchiveLoader(Path archive, ClassLoader parent) throws IOException {
        super(new URL[] {archive.toUri().toURL()}, parent);
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        if (!name.startsWith("imgui.")) throw new ClassNotFoundException("Not an ImGui probe class: " + name);
        var location = findResource(name.replace('.', '/') + ".class");
        if (location == null) throw new ClassNotFoundException(name);
        try (var input = location.openStream()) {
            byte[] bytes = preparation.prepare(input.readAllBytes()).requireBytecode();
            return defineClass(name, bytes, 0, bytes.length);
        } catch (IOException | RuntimeException failure) {
            throw new ClassNotFoundException(name, failure);
        }
    }
}
