package dev.fopwoc.julia.profiling.run;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Just enough JSON for scene files and the game's launcher settings. This jar joins the game's class path, so bundling a
 * JSON library could shadow the one the game ships. Objects are maps, arrays lists, and numbers doubles.
 */
final class Json {
    private final String text;
    private int at;

    private Json(String text) {
        this.text = text;
    }

    static Object parse(String text) {
        var json = new Json(text);
        var value = json.value();
        json.space();
        if (json.at != text.length()) throw json.invalid("trailing content");
        return value;
    }

    private Object value() {
        space();
        if (at >= text.length()) throw invalid("unexpected end");
        char next = text.charAt(at);
        if (next == '{') return object();
        if (next == '[') return array();
        if (next == '"') return string();
        for (var literal : List.of("true", "false", "null")) {
            if (text.startsWith(literal, at)) {
                at += literal.length();
                return literal.equals("null") ? null : Boolean.valueOf(literal);
            }
        }
        int start = at;
        while (at < text.length() && "+-.eE0123456789".indexOf(text.charAt(at)) >= 0) at++;
        if (start == at) throw invalid("unexpected character");
        try {
            return Double.valueOf(text.substring(start, at));
        } catch (NumberFormatException failure) {
            throw invalid("bad number");
        }
    }

    private Map<String, Object> object() {
        var result = new LinkedHashMap<String, Object>();
        at++;
        space();
        if (peek('}')) return result;
        do {
            space();
            if (at >= text.length() || text.charAt(at) != '"') throw invalid("expected a key");
            var key = string();
            space();
            if (!peek(':')) throw invalid("expected ':'");
            result.put(key, value());
            space();
        } while (peek(','));
        if (!peek('}')) throw invalid("expected '}'");
        return result;
    }

    private List<Object> array() {
        var result = new ArrayList<Object>();
        at++;
        space();
        if (peek(']')) return result;
        do {
            result.add(value());
            space();
        } while (peek(','));
        if (!peek(']')) throw invalid("expected ']'");
        return result;
    }

    private String string() {
        var result = new StringBuilder();
        at++;
        while (true) {
            if (at >= text.length()) throw invalid("unterminated string");
            char next = text.charAt(at++);
            if (next == '"') return result.toString();
            if (next != '\\') {
                result.append(next);
                continue;
            }
            if (at >= text.length()) throw invalid("unterminated escape");
            char escape = text.charAt(at++);
            switch (escape) {
                case 'n' -> result.append('\n');
                case 't' -> result.append('\t');
                case 'r' -> result.append('\r');
                case 'b' -> result.append('\b');
                case 'f' -> result.append('\f');
                case 'u' -> {
                    if (at + 4 > text.length()) throw invalid("short unicode escape");
                    result.append((char) Integer.parseInt(text.substring(at, at + 4), 16));
                    at += 4;
                }
                default -> result.append(escape);
            }
        }
    }

    private boolean peek(char expected) {
        if (at < text.length() && text.charAt(at) == expected) {
            at++;
            return true;
        }
        return false;
    }

    private void space() {
        while (at < text.length() && Character.isWhitespace(text.charAt(at))) at++;
    }

    private IllegalArgumentException invalid(String reason) {
        return new IllegalArgumentException("Invalid JSON at character " + at + ": " + reason);
    }
}
