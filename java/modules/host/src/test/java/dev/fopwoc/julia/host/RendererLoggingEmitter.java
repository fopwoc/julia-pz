package dev.fopwoc.julia.host;

import dev.fopwoc.julia.utils.logging.LogConfiguration;
import org.apache.logging.log4j.LogManager;

/** Loaded independently by each real graphics class loader; no renderer or native initialization. */
public final class RendererLoggingEmitter {
    private RendererLoggingEmitter() {}

    public static void emit() {
        LogConfiguration.configure();
        LogConfiguration.configure();
        var factory = LogManager.getFactory();
        if (!factory.getClass().getName().equals("dev.fopwoc.julia.backend.logging.RendererLoggerContextFactory")
                || factory.getClass().getClassLoader() != RendererLoggingEmitter.class.getClassLoader()) {
            throw new AssertionError("Logging provider escaped the isolated loader: " + factory.getClass());
        }
        String name = "arbitrary-renderer/name";
        var library = LogManager.getLogger(name);
        if (!library.getName().equals("dev.fopwoc.julia.renderer." + name)
                || library != LogManager.getLogger(name)
                || !LogManager.getContext(false).hasLogger(name)) {
            throw new AssertionError("Library logger naming or caching is inconsistent");
        }
        library.debug("library-debug {}", "value");
        library.info("library-info {}", "value");
        library.warn("library-warn {}", "value");
        library.error("library-error {}", "value");
        library.fatal("library-fatal {}", "value");
        library.error("fault {}", "value", new IllegalStateException("logging-fixture-failure"));
        emitOwned("com.gtnewhorizons.angelica.LoggingFixture", "owned");
        emitOwned("com.gtnewhorizon.gtnhlib.LoggingFixture", "helper");
    }

    private static void emitOwned(String name, String prefix) {
        var logger = System.getLogger(name);
        logger.log(System.Logger.Level.DEBUG, prefix + "-debug {0}", "value");
        logger.log(System.Logger.Level.INFO, prefix + "-info {0}", "value");
        logger.log(System.Logger.Level.WARNING, prefix + "-warn {0}", "value");
        logger.log(System.Logger.Level.ERROR, prefix + "-error {0}", "value");
    }
}
