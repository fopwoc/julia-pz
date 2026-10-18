package dev.fopwoc.julia.runtime;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Objects;
import java.util.Set;

/** Resolves only explicitly permitted graphics endpoints inside a separate class loader. */
public final class IsolatedTargets implements RenderSession.Targets {
    private final ClassLoader loader;
    private final Set<String> owners;

    public IsolatedTargets(ClassLoader loader, Set<String> owners) {
        this.loader = Objects.requireNonNull(loader);
        this.owners = Set.copyOf(owners);
    }

    @Override
    public MethodHandle resolve(String owner, String name, MethodType type) throws ReflectiveOperationException {
        if (!owners.contains(owner)) throw new IllegalArgumentException("Unapproved graphics endpoint: " + owner);
        checkType(type.returnType());
        for (Class<?> argument : type.parameterArray()) checkType(argument);
        var endpoint = Class.forName(owner.replace('/', '.'), false, loader);
        if (endpoint.getClassLoader() != loader)
            throw new IllegalArgumentException("Graphics endpoint escaped its isolated loader: " + owner);
        return MethodHandles.publicLookup().findStatic(endpoint, name, type);
    }

    private static void checkType(Class<?> type) {
        while (type.isArray()) type = type.componentType();
        if (!type.isPrimitive() && type.getClassLoader() != null) {
            throw new IllegalArgumentException(
                    "Non-bootstrap type crosses graphics runtime boundary: " + type.getName());
        }
    }
}
