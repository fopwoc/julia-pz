package dev.julia.probe.redirect;

import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;

/** Reads published target signatures without loading or initializing graphics classes. */
final class TargetAbi {
    private final HashMap<String, Set<String>> methods = new HashMap<>();

    boolean contains(String owner, String name, String descriptor) throws IOException {
        var signatures = methods.get(owner);
        if (signatures == null) {
            signatures = new HashSet<>();
            try (var input = TargetAbi.class.getResourceAsStream("/" + owner + ".class")) {
                if (input == null) throw new IOException("Missing backend class resource: " + owner);
                for (var method : ClassFile.of().parse(input.readAllBytes()).methods()) {
                    int access = method.flags().flagsMask();
                    if ((access & (ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC)) == (ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC)) {
                        signatures.add(method.methodName().stringValue() + method.methodType().stringValue());
                    }
                }
            }
            methods.put(owner, signatures);
        }
        return signatures.contains(name + descriptor);
    }
}
