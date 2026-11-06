package dev.fopwoc.julia.preload;

import dev.fopwoc.julia.agent.JuliaAgent;
import dev.fopwoc.julia.runtime.JuliaStatus;
import java.lang.instrument.Instrumentation;
import java.nio.file.Path;
import java.util.Map;
import java.util.jar.JarFile;

/**
 * ZombieBuddy's preload entry point, which runs Julia as a signed mod instead of a separate {@code -javaagent}.
 *
 * <p>ZombieBuddy injects mod classes without a code source and passes no {@link Instrumentation}, so both have to come
 * from its loader. It offers no API for them, and these internals exist in 2.3.4 and 3.x alike; if they ever move this
 * fails loudly rather than leaving the game on an untranslated renderer without saying why.
 */
public final class PreMain {
    private static final String BUDDY_LOADER = "me.zed_0xff.zombie_buddy.Loader";
    private static final String BUDDY_EXPOSER = "me.zed_0xff.zombie_buddy.Exposer";

    private PreMain() {}

    public static void premain(String[] arguments) throws Exception {
        // An explicit -javaagent entry already armed the same startup before this mod could run.
        if (System.getProperty("julia.startup") != null) return;
        var loader = Class.forName(BUDDY_LOADER);
        var instrumentation = (Instrumentation) staticField(loader, "g_instrumentation");
        if (instrumentation == null) throw new IllegalStateException("ZombieBuddy exposes no instrumentation");
        JuliaAgent.activate("activate", instrumentation, archive(staticField(loader, "g_config")));
        // ZombieBuddy has already loaded the Lua exposer, so Julia's own pass cannot add this global.
        Class.forName(BUDDY_EXPOSER)
                .getMethod("exposeClass", Class.class, String.class)
                .invoke(null, JuliaStatus.class, "JuliaStatus");
    }

    /** Found by content, not mod id, so a local development copy can run under its own id beside the Workshop item. */
    private static Path archive(Object config) throws Exception {
        for (var mod : ((Map<?, ?>) accessor(config, "preload_mods")).values()) {
            var jar = (Path) accessor(mod, "jarPath");
            try (var file = new JarFile(jar.toFile())) {
                if (file.getEntry(PreMain.class.getName().replace('.', '/') + ".class") != null) return jar;
            }
        }
        throw new IllegalStateException("ZombieBuddy lists no preload mod containing Julia");
    }

    private static Object staticField(Class<?> owner, String name) throws ReflectiveOperationException {
        var field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(null);
    }

    private static Object accessor(Object owner, String name) throws ReflectiveOperationException {
        var method = owner.getClass().getDeclaredMethod(name);
        method.setAccessible(true);
        return method.invoke(owner);
    }
}
