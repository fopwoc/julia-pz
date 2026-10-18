package dev.fopwoc.julia.transform;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.classfile.ClassFile;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Public static method signatures of endpoint classes, read from class resources without loading them. */
public final class TargetAbi {
    private static final int PUBLIC_STATIC = ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC;

    private final Map<String, Set<String>> signatures = new ConcurrentHashMap<>();

    public boolean contains(String owner, String name, String descriptor) throws IOException {
        try {
            return signatures.computeIfAbsent(owner, TargetAbi::read).contains(name + descriptor);
        } catch (UncheckedIOException failure) {
            throw failure.getCause();
        }
    }

    private static Set<String> read(String owner) {
        try (var input = TargetAbi.class.getResourceAsStream("/" + owner + ".class")) {
            if (input == null) throw new IOException("Missing endpoint class resource: " + owner);
            var result = new HashSet<String>();
            for (var method : ClassFile.of().parse(input.readAllBytes()).methods()) {
                if ((method.flags().flagsMask() & PUBLIC_STATIC) == PUBLIC_STATIC) {
                    result.add(method.methodName().stringValue()
                            + method.methodType().stringValue());
                }
            }
            return Set.copyOf(result);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }
}
