package dev.fopwoc.julia.transform.pass;

/**
 * LWJGL's core OpenGL classes. Each {@code GLnn} (and core-profile {@code GLnnC}) class inherits every earlier
 * version's functions, so a call names whichever version the caller imported; routing keys the function, not the
 * owner.
 */
final class CoreOwners {
    static final String LWJGL = "org/lwjgl/opengl/";
    private static final String CORE = "GL.";

    private CoreOwners() {}

    /** {@code GL.name descriptor} for any core owner; {@code owner.name descriptor} for every other owner. */
    static String key(String owner, String name, String descriptor) {
        return (is(owner) ? CORE : owner + ".") + name + descriptor;
    }

    /** The key a core function routes under, whatever version class the caller named. */
    static String core(String nameAndDescriptor) {
        return CORE + nameAndDescriptor;
    }

    /** {@code org/lwjgl/opengl/GL} followed by a version number and an optional core-profile {@code C}. */
    static boolean is(String owner) {
        int start = LWJGL.length() + 2;
        if (!owner.startsWith(LWJGL + "GL")) return false;
        int end = owner.endsWith("C") ? owner.length() - 1 : owner.length();
        if (end <= start) return false;
        for (int i = start; i < end; i++) if (!Character.isDigit(owner.charAt(i))) return false;
        return true;
    }
}
