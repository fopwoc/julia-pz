package dev.fopwoc.julia.profiling.agent;

import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassHierarchyResolver;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.zip.ZipFile;

/** Offline hook verification reads class resources only; it never initializes or launches game classes. */
public final class FrameAudit {
    private FrameAudit() {}

    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 1) throw new IllegalArgumentException("Supply projectzomboid.jar");
        var path = Path.of(arguments[0]);
        try (var source = new ZipFile(path.toFile());
                var resources =
                        new URLClassLoader(new URL[] {path.toUri().toURL()}, FrameAudit.class.getClassLoader())) {
            for (String target : new String[] {FrameInstrumentation.TARGET, LoadingInstrumentation.TARGET}) {
                byte[] original;
                try (var input = source.getInputStream(source.getEntry(target + ".class"))) {
                    original = input.readAllBytes();
                }
                var transformed = target.equals(FrameInstrumentation.TARGET)
                        ? FrameInstrumentation.instrument(original, resources)
                        : LoadingInstrumentation.instrument(original, resources);
                var errors = ClassFile.of(ClassFile.ClassHierarchyResolverOption.of(
                                ClassHierarchyResolver.ofResourceParsing(resources)))
                        .verify(transformed);
                if (!errors.isEmpty()) throw new IllegalStateException(errors.toString());
                System.out.println("PASS: " + target + " bytecode verified; no game classes initialized");
            }
        }
    }
}
