package dev.julia.audit.model;

public record GraphicsSymbol(Kind kind, String owner, String name, String descriptor)
        implements Comparable<GraphicsSymbol> {
    public enum Kind { METHOD, FIELD }

    public boolean isGraphicsBinding() {
        return owner.startsWith("org/lwjgl/opengl/")
                || owner.startsWith("org/lwjgl/glfw/")
                || owner.startsWith("org/lwjgl/util/glu/")
                || owner.startsWith("org/lwjglx/opengl/");
    }

    @Override
    public int compareTo(GraphicsSymbol other) {
        int order = owner.compareTo(other.owner);
        if (order == 0) order = name.compareTo(other.name);
        if (order == 0) order = descriptor.compareTo(other.descriptor);
        if (order == 0) order = kind.compareTo(other.kind);
        return order;
    }
}
