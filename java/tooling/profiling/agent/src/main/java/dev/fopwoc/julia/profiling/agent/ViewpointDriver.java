package dev.fopwoc.julia.profiling.agent;

/**
 * Puts the Viewpoint mod in first person. Profiling can reach into a mod like this because it never ships; Julia itself
 * stays independent of any mod. If the mod renames something, the run stops with an error rather than measuring the wrong
 * view.
 */
final class ViewpointDriver {
    private ViewpointDriver() {}

    /**
     * One step toward first person: {@code true} once there. A view change only takes effect on a later frame, so this
     * cycles at most once per call and the caller waits between calls.
     */
    static boolean firstPerson() throws ReflectiveOperationException {
        var loader = ClassLoader.getSystemClassLoader();
        var view = Class.forName("viewpoint.core.View", true, loader);
        if (view.getField("enabled").getBoolean(null)
                && !view.getField("thirdPerson").getBoolean(null)) return true;
        Class.forName("viewpoint.ViewLua", true, loader).getMethod("cycle").invoke(null);
        return false;
    }
}
