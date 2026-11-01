package dev.fopwoc.julia.backend.logging;

import java.net.URI;
import org.apache.logging.log4j.message.MessageFactory;
import org.apache.logging.log4j.spi.ExtendedLogger;
import org.apache.logging.log4j.spi.LoggerContext;
import org.apache.logging.log4j.spi.LoggerContextFactory;
import org.apache.logging.log4j.tojul.JULLoggerContextFactory;

/** Keeps arbitrary library logger names under Julia's configured JUL namespace. */
public final class RendererLoggerContextFactory implements LoggerContextFactory {
    static final String PREFIX = "dev.fopwoc.julia.renderer.";
    private final LoggerContext context;

    public RendererLoggerContextFactory() {
        var jul = new JULLoggerContextFactory();
        context = new NamespacedContext(jul.getContext(null, null, null, false));
    }

    @Override
    public LoggerContext getContext(String fqcn, ClassLoader loader, Object externalContext, boolean currentContext) {
        return context;
    }

    @Override
    public LoggerContext getContext(
            String fqcn,
            ClassLoader loader,
            Object externalContext,
            boolean currentContext,
            URI configLocation,
            String name) {
        return context;
    }

    @Override
    public void removeContext(LoggerContext ignored) {}

    @Override
    public boolean isClassLoaderDependent() {
        return false;
    }

    private record NamespacedContext(LoggerContext delegate) implements LoggerContext {
        @Override
        public Object getExternalContext() {
            return delegate.getExternalContext();
        }

        @Override
        public ExtendedLogger getLogger(String name) {
            return delegate.getLogger(PREFIX + name);
        }

        @Override
        public ExtendedLogger getLogger(String name, MessageFactory factory) {
            return delegate.getLogger(PREFIX + name, factory);
        }

        @Override
        public boolean hasLogger(String name) {
            return delegate.hasLogger(PREFIX + name);
        }

        @Override
        public boolean hasLogger(String name, MessageFactory factory) {
            return delegate.hasLogger(PREFIX + name, factory);
        }

        @Override
        public boolean hasLogger(String name, Class<? extends MessageFactory> factory) {
            return delegate.hasLogger(PREFIX + name, factory);
        }
    }
}
