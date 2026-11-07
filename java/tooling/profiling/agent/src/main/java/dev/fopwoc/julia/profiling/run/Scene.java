package dev.fopwoc.julia.profiling.run;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** What one batch measures: a save, the spots visited in it, and how long each is recorded. */
public record Scene(
        String name, String save, Duration warmup, Duration duration, List<String> jvmOptions, List<Spot> spots) {
    private static final String SAFE_NAME = "[A-Za-z0-9_.-]{1,64}";
    private static final String SAVE_PART = "[^/\\\\\\p{Cntrl}]+";

    /** {@code ghost} stops zombies reacting; leave it off where their load is what is being measured. */
    public record Spot(String name, Position position, Double hour, boolean ghost) {}

    public record Position(float x, float y, int z) {}

    public String mode() {
        return save.substring(0, save.indexOf('/'));
    }

    public String world() {
        return save.substring(save.indexOf('/') + 1);
    }

    public static Scene read(Path file) throws IOException {
        return parse(Files.readString(file));
    }

    @SuppressWarnings("unchecked")
    static Scene parse(String text) {
        if (!(Json.parse(text) instanceof Map<?, ?> raw))
            throw new IllegalArgumentException("A scene is a JSON object");
        var data = (Map<String, Object>) raw;
        known(data, Set.of("name", "save", "warmup", "duration", "jvmOptions", "spots"), "scene");
        String name = text(data, "name");
        if (!name.matches(SAFE_NAME)) throw new IllegalArgumentException("Scene needs a file-safe name");
        String save = text(data, "save");
        if (!save.matches(SAVE_PART + "/" + SAVE_PART)
                || save.contains("..")
                || save.startsWith("Multiplayer/")
                || save.startsWith("LastStand/")) {
            throw new IllegalArgumentException("Save must be an existing single-player Mode/SaveName");
        }
        var warmup = seconds(data, "warmup", 20, 0);
        var duration = seconds(data, "duration", 60, 0.001);
        var options = new ArrayList<String>();
        for (var option : list(data, "jvmOptions")) {
            if (!(option instanceof String value)) throw new IllegalArgumentException("JVM options must be strings");
            options.add(value);
        }
        var spots = new ArrayList<Spot>();
        var listed = data.containsKey("spots") ? list(data, "spots") : List.<Object>of(Map.of("name", name));
        for (var entry : listed) {
            if (!(entry instanceof Map<?, ?> spot)) throw new IllegalArgumentException("A spot is a JSON object");
            spots.add(spot((Map<String, Object>) spot));
        }
        if (spots.isEmpty()) throw new IllegalArgumentException("Spots must not be empty");
        if (spots.stream().map(Spot::name).distinct().count() != spots.size())
            throw new IllegalArgumentException("Spot names must be unique");
        return new Scene(name, save, warmup, duration, List.copyOf(options), List.copyOf(spots));
    }

    private static Spot spot(Map<String, Object> data) {
        known(data, Set.of("name", "position", "hour", "ghost"), "spot");
        String name = text(data, "name");
        if (!name.matches(SAFE_NAME)) throw new IllegalArgumentException("Spot needs a file-safe name");
        Position position = null;
        if (data.get("position") != null) {
            if (!(data.get("position") instanceof List<?> values)
                    || values.size() != 3
                    || !(values.get(0) instanceof Double x)
                    || !(values.get(1) instanceof Double y)
                    || !(values.get(2) instanceof Double z)
                    || x < 0
                    || y < 0
                    || z != Math.rint(z)) {
                throw new IllegalArgumentException("Position must be [x, y, integer z] in the game world");
            }
            position = new Position(x.floatValue(), y.floatValue(), z.intValue());
        }
        Double hour = null;
        if (data.get("hour") != null) {
            if (!(data.get("hour") instanceof Double value) || value < 0 || value >= 24)
                throw new IllegalArgumentException("Hour must be in [0, 24)");
            hour = value;
        }
        Object ghost = data.getOrDefault("ghost", false);
        if (!(ghost instanceof Boolean enabled)) throw new IllegalArgumentException("Ghost must be true or false");
        return new Spot(name, position, hour, enabled);
    }

    private static void known(Map<String, Object> data, Set<String> fields, String kind) {
        for (var field : data.keySet())
            if (!fields.contains(field)) throw new IllegalArgumentException("Unknown " + kind + " field: " + field);
    }

    private static String text(Map<String, Object> data, String field) {
        if (!(data.get(field) instanceof String value)) throw new IllegalArgumentException("Missing " + field);
        return value;
    }

    private static List<Object> list(Map<String, Object> data, String field) {
        if (data.get(field) == null) return List.of();
        if (!(data.get(field) instanceof List<?> value)) throw new IllegalArgumentException(field + " must be a list");
        return new ArrayList<>(value);
    }

    private static Duration seconds(Map<String, Object> data, String field, double fallback, double minimum) {
        Object value = data.getOrDefault(field, fallback);
        if (!(value instanceof Double seconds) || !Double.isFinite(seconds) || seconds < minimum)
            throw new IllegalArgumentException("Invalid " + field);
        return Duration.ofMillis(Math.round(seconds * 1000));
    }
}
