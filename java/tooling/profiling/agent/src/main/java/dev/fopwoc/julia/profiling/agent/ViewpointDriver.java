package dev.fopwoc.julia.profiling.agent;

/**
 * Puts the Viewpoint mod in first person. Profiling can reach into a mod like this because it never ships; Julia itself
 * stays independent of any mod. If the mod renames something, the run stops with an error rather than measuring the wrong
 * view.
 */
final class ViewpointDriver {
    private static final int MAX_CYCLES = 4;

    private ViewpointDriver() {}

    static void firstPerson() throws ReflectiveOperationException {
        var loader = ClassLoader.getSystemClassLoader();
        var view = Class.forName("viewpoint.core.View", true, loader);
        var cycle = Class.forName("viewpoint.ViewLua", true, loader).getMethod("cycle");
        for (int attempt = 0; attempt <= MAX_CYCLES; attempt++) {
            if (view.getField("enabled").getBoolean(null)
                    && !view.getField("thirdPerson").getBoolean(null)) return;
            cycle.invoke(null);
        }
        throw new IllegalStateException("Viewpoint didn't enter first person");
    }
}
