package dev.fopwoc.julia.utils.logging;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Arrays;
import java.util.Locale;
import java.util.logging.ConsoleHandler;
import java.util.logging.Formatter;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * Routes {@code dev.fopwoc.julia} {@link System.Logger} output to stderr at the {@code LOG_LEVEL} environment level.
 *
 * <p>JUL is JVM-global while this class may be loaded once per class loader, so repeated calls are no-ops.
 */
public final class LogConfiguration {
    private static final String ROOT = "dev.fopwoc.julia";

    private LogConfiguration() {}

    public static void configure() {
        var logger = Logger.getLogger(ROOT);
        synchronized (Logger.class) {
            // Compare by name: another loader's copy of this class may already have installed its handler.
            if (Arrays.stream(logger.getHandlers())
                    .anyMatch(handler -> handler.getFormatter() != null
                            && handler.getFormatter().getClass().getName().equals(CompactFormat.class.getName()))) {
                return;
            }
            logger.setUseParentHandlers(false);
            logger.setLevel(level(System.getenv().getOrDefault("LOG_LEVEL", "error")));
            var handler = new ConsoleHandler();
            handler.setLevel(Level.ALL);
            handler.setFormatter(new CompactFormat());
            logger.addHandler(handler);
        }
    }

    static Level level(String name) {
        return switch (name.toLowerCase(Locale.ROOT)) {
            case "debug" -> Level.FINE;
            case "info" -> Level.INFO;
            case "warn" -> Level.WARNING;
            case "error" -> Level.SEVERE;
            default -> throw new IllegalArgumentException("LOG_LEVEL must be debug, info, warn, or error");
        };
    }

    private static final class CompactFormat extends Formatter {
        @Override
        public String format(LogRecord record) {
            var text = new StringBuilder()
                    .append(record.getLevel())
                    .append(' ')
                    .append(record.getLoggerName())
                    .append(": ")
                    .append(formatMessage(record))
                    .append('\n');
            if (record.getThrown() != null) {
                var stack = new StringWriter();
                record.getThrown().printStackTrace(new PrintWriter(stack));
                text.append(stack);
            }
            return text.toString();
        }
    }
}
