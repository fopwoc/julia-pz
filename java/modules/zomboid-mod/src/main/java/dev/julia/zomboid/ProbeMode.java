package dev.julia.zomboid;

import java.util.Locale;

/** Which probe the mod runs, chosen with {@code -Djulia.probe}. */
enum ProbeMode {
    /** Offscreen device baseline plus the full adapter; never touches the game window. */
    ADAPTER,
    /** Device baseline plus presentation into the game's window; experimental. */
    WINDOW;

    static ProbeMode fromSystemProperty() {
        String value = System.getProperty("julia.probe", "adapter");
        try {
            return valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("Unknown Julia probe: " + value, failure);
        }
    }

    @Override
    public String toString() {
        return name().toLowerCase(Locale.ROOT);
    }
}
