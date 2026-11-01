package dev.fopwoc.julia.backend.logging;

import org.apache.logging.log4j.spi.LoggerContextFactory;
import org.apache.logging.log4j.spi.NoOpThreadContextMap;
import org.apache.logging.log4j.spi.Provider;
import org.apache.logging.log4j.spi.ThreadContextMap;

/** Selects the namespaced JUL adapter for libraries inside Julia's graphics runtime. */
public final class RendererLoggingProvider extends Provider {
    private static final LoggerContextFactory FACTORY = new RendererLoggerContextFactory();

    public RendererLoggingProvider() {
        super(30, CURRENT_VERSION, RendererLoggerContextFactory.class, NoOpThreadContextMap.class);
    }

    @Override
    public LoggerContextFactory getLoggerContextFactory() {
        return FACTORY;
    }

    @Override
    public ThreadContextMap getThreadContextMapInstance() {
        return NoOpThreadContextMap.INSTANCE;
    }
}
