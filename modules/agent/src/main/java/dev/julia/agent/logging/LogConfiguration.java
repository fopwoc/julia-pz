package dev.julia.agent.logging;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Locale;
import java.util.logging.ConsoleHandler;
import java.util.logging.Formatter;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

public final class LogConfiguration {
    private LogConfiguration() { }

    public static void configure() {
        String configured = System.getenv().getOrDefault("LOG_LEVEL", "error").toLowerCase(Locale.ROOT);
        Level level = switch (configured) {
            case "debug" -> Level.FINE;
            case "info" -> Level.INFO;
            case "warn" -> Level.WARNING;
            case "error" -> Level.SEVERE;
            default -> throw new IllegalArgumentException("LOG_LEVEL must be debug, info, warn, or error");
        };
        var logger = Logger.getLogger("dev.julia");
        logger.setUseParentHandlers(false);
        logger.setLevel(level);
        var handler = new ConsoleHandler();
        handler.setLevel(Level.ALL);
        handler.setFormatter(new Formatter() {
            @Override
            public String format(LogRecord record) {
                var text = new StringBuilder().append(record.getLevel()).append(' ')
                        .append(record.getLoggerName()).append(": ").append(formatMessage(record)).append('\n');
                if (record.getThrown() != null) {
                    var stack = new StringWriter();
                    record.getThrown().printStackTrace(new PrintWriter(stack));
                    text.append(stack);
                }
                return text.toString();
            }
        });
        logger.addHandler(handler);
    }
}
