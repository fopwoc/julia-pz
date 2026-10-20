package dev.fopwoc.julia.backend.query;

import static org.lwjgl.opengl.GL15.GL_QUERY_RESULT;
import static org.lwjgl.opengl.GL15.GL_QUERY_RESULT_AVAILABLE;
import static org.lwjgl.opengl.GL15.GL_SAMPLES_PASSED;
import static org.lwjgl.opengl.GL33.GL_ANY_SAMPLES_PASSED;
import static org.lwjgl.opengl.GL33.GL_TIMESTAMP;
import static org.lwjgl.opengl.GL33.GL_TIME_ELAPSED;
import static org.lwjgl.opengl.GL43.GL_ANY_SAMPLES_PASSED_CONSERVATIVE;

import java.util.HashMap;
import java.util.function.Supplier;

/**
 * GL query names, target ownership, pending results, and deletion for timer and occlusion queries; every result comes
 * from GPU samples. All query kinds share one name space, as in GL.
 */
public final class Queries implements AutoCloseable {
    private static final class Query {
        int target;
        // Timer queries hold timestamp samples; occlusion queries hold their visibility handle in start.
        long start;
        long end;
        boolean ended;
        Long result;
    }

    private final HashMap<Integer, Query> queries = new HashMap<>();
    private final Supplier<TimestampDriver> timestamps;
    private final Supplier<OcclusionDriver> occlusion;
    private TimestampDriver timestampDriver;
    private OcclusionDriver occlusionDriver;
    private int next = 1;
    private int activeTimer;
    private int activeOcclusion;

    public Queries(Supplier<TimestampDriver> timestamps, Supplier<OcclusionDriver> occlusion) {
        this.timestamps = timestamps;
        this.occlusion = occlusion;
    }

    public int generate() {
        if (next <= 0) throw new IllegalStateException("Query name space exhausted");
        int name = next++;
        queries.put(name, new Query());
        return name;
    }

    public boolean isQuery(int name) {
        var query = queries.get(name);
        return query != null && query.target != 0;
    }

    public void delete(int name) {
        if (name != 0 && (activeTimer == name || activeOcclusion == name))
            throw new IllegalArgumentException("Cannot delete an active query");
        var query = queries.remove(name);
        if (query != null) release(query);
    }

    public void timestamp(int name, int target) {
        if (target != GL_TIMESTAMP) throw new IllegalArgumentException("Query counter target must be GL_TIMESTAMP");
        var query = typed(name, target);
        release(query);
        query.end = timestamps().record();
        query.ended = true;
    }

    public void begin(int target, int name) {
        if (target == GL_TIME_ELAPSED) {
            if (activeTimer != 0) throw new IllegalStateException("An elapsed timer query is already active");
            var query = typed(name, target);
            release(query);
            query.start = timestamps().record();
            activeTimer = name;
        } else if (occlusion(target)) {
            // GL allows one active query per occlusion target; the native counter supports one at a time overall.
            if (activeOcclusion != 0) throw new IllegalStateException("An occlusion query is already active");
            var query = typed(name, target);
            release(query);
            query.start = occlusion().begin();
            activeOcclusion = name;
        } else throw new UnsupportedOperationException("Unsupported query target: 0x" + Integer.toHexString(target));
    }

    public void end(int target) {
        if (target == GL_TIME_ELAPSED) {
            if (activeTimer == 0) throw new IllegalStateException("No matching elapsed timer query");
            var query = queries.get(activeTimer);
            query.end = timestamps().record();
            query.ended = true;
            activeTimer = 0;
        } else if (occlusion(target)) {
            var query = activeOcclusion == 0 ? null : queries.get(activeOcclusion);
            if (query == null || query.target != target) throw new IllegalStateException("No matching occlusion query");
            occlusion().end(query.start);
            query.ended = true;
            activeOcclusion = 0;
        } else throw new IllegalStateException("No matching query for target: 0x" + Integer.toHexString(target));
    }

    public long get(int name, int parameter) {
        if (parameter != GL_QUERY_RESULT && parameter != GL_QUERY_RESULT_AVAILABLE)
            throw new UnsupportedOperationException("Unsupported query parameter: " + parameter);
        var query = queries.get(name);
        if (query == null || query.target == 0 || !query.ended)
            throw new IllegalArgumentException("Query has no completed measurement: " + name);
        boolean counted = occlusion(query.target);
        if (parameter == GL_QUERY_RESULT_AVAILABLE)
            return query.result != null
                            || (counted
                                    ? occlusion().available(query.start)
                                    : timestamps().available(query.end))
                    ? 1
                    : 0;
        if (query.result == null) query.result = counted ? samples(query) : elapsed(query);
        return query.result;
    }

    private long samples(Query query) {
        long samples = occlusion().result(query.start);
        return query.target == GL_SAMPLES_PASSED ? samples : samples != 0 ? 1 : 0;
    }

    private long elapsed(Query query) {
        long end = timestamps().result(query.end);
        if (query.target == GL_TIMESTAMP) return end;
        long elapsed = Math.subtractExact(end, timestamps().result(query.start));
        if (elapsed < 0) throw new IllegalStateException("GPU elapsed time was negative");
        return elapsed;
    }

    private static boolean occlusion(int target) {
        return target == GL_SAMPLES_PASSED
                || target == GL_ANY_SAMPLES_PASSED
                || target == GL_ANY_SAMPLES_PASSED_CONSERVATIVE;
    }

    private Query typed(int name, int target) {
        var query = queries.get(name);
        if (query == null) throw new IllegalArgumentException("Query name was not generated: " + name);
        if (query.target != 0 && query.target != target) throw new IllegalArgumentException("Query target changed");
        query.target = target;
        return query;
    }

    private TimestampDriver timestamps() {
        if (timestampDriver == null) timestampDriver = timestamps.get();
        return timestampDriver;
    }

    private OcclusionDriver occlusion() {
        if (occlusionDriver == null) occlusionDriver = occlusion.get();
        return occlusionDriver;
    }

    private void release(Query query) {
        if (occlusion(query.target)) {
            if (occlusionDriver != null) occlusionDriver.release(query.start);
        } else if (timestampDriver != null) {
            timestampDriver.release(query.start);
            timestampDriver.release(query.end);
        }
        query.start = 0;
        query.end = 0;
        query.ended = false;
        query.result = null;
    }

    @Override
    public void close() {
        for (var query : queries.values()) release(query);
        queries.clear();
        activeTimer = 0;
        activeOcclusion = 0;
        if (timestampDriver != null) {
            timestampDriver.close();
            timestampDriver = null;
        }
        if (occlusionDriver != null) {
            occlusionDriver.close();
            occlusionDriver = null;
        }
    }
}
