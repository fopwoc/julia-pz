package dev.julia.audit.model;

import java.util.Comparator;

public record GraphicsSymbol(Kind kind, String owner, String name, String descriptor)
        implements Comparable<GraphicsSymbol> {
    public enum Kind {
        METHOD,
        FIELD
    }

    public boolean isGraphicsBinding() {
        return owner.startsWith("org/lwjgl/opengl/")
                || owner.startsWith("org/lwjgl/glfw/")
                || owner.startsWith("org/lwjgl/util/glu/")
                || owner.startsWith("org/lwjglx/opengl/");
    }

    private static final Comparator<GraphicsSymbol> ORDER = Comparator.comparing(GraphicsSymbol::owner)
            .thenComparing(GraphicsSymbol::name)
            .thenComparing(GraphicsSymbol::descriptor)
            .thenComparing(GraphicsSymbol::kind);

    @Override
    public int compareTo(GraphicsSymbol other) {
        return ORDER.compare(this, other);
    }
}
