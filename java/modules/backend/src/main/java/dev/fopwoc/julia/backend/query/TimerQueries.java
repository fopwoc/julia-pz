package dev.fopwoc.julia.backend.query;

import static org.lwjgl.opengl.GL15.GL_QUERY_RESULT;
import static org.lwjgl.opengl.GL15.GL_QUERY_RESULT_AVAILABLE;
import static org.lwjgl.opengl.GL33.GL_TIMESTAMP;
import static org.lwjgl.opengl.GL33.GL_TIME_ELAPSED;

import java.util.HashMap;
import java.util.function.Supplier;

/** GL query names, target ownership, pending results, and deletion; every result comes from GPU samples. */
public final class TimerQueries implements AutoCloseable {
    private static final class Query {
        int target;
        long start;
        long end;
        Long result;
    }

    private final HashMap<Integer, Query> queries = new HashMap<>();
    private final Supplier<TimestampDriver> factory;
    private TimestampDriver driver;
    private int next = 1;
    private int active;

    public TimerQueries(Supplier<TimestampDriver> factory) {
        this.factory = factory;
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
        if (active == name && name != 0) throw new IllegalArgumentException("Cannot delete an active query");
        var query = queries.remove(name);
        if (query != null) release(query);
    }

    public void timestamp(int name, int target) {
        if (target != GL_TIMESTAMP) throw new IllegalArgumentException("Query counter target must be GL_TIMESTAMP");
        var query = typed(name, target);
        release(query);
        query.end = driver().record();
    }

    public void begin(int target, int name) {
        if (target != GL_TIME_ELAPSED)
            throw new UnsupportedOperationException("Only timer queries are implemented; target=" + target);
        if (active != 0) throw new IllegalStateException("An elapsed timer query is already active");
        var query = typed(name, target);
        release(query);
        query.start = driver().record();
        active = name;
    }

    public void end(int target) {
        if (target != GL_TIME_ELAPSED || active == 0)
            throw new IllegalStateException("No matching elapsed timer query");
        queries.get(active).end = driver().record();
        active = 0;
    }

    public long get(int name, int parameter) {
        if (parameter != GL_QUERY_RESULT && parameter != GL_QUERY_RESULT_AVAILABLE)
            throw new UnsupportedOperationException("Unsupported timer query parameter: " + parameter);
        var query = queries.get(name);
        if (query == null || query.target == 0 || name == active || query.end == 0)
            throw new IllegalArgumentException("Query has no completed measurement: " + name);
        if (parameter == GL_QUERY_RESULT_AVAILABLE)
            return query.result != null || driver().available(query.end) ? 1 : 0;
        if (query.result == null) {
            long end = driver().result(query.end);
            query.result = query.target == GL_TIMESTAMP ? end : Math.subtractExact(end, driver().result(query.start));
            if (query.result < 0) throw new IllegalStateException("GPU elapsed time was negative");
        }
        return query.result;
    }

    private Query typed(int name, int target) {
        var query = queries.get(name);
        if (query == null) throw new IllegalArgumentException("Query name was not generated: " + name);
        if (query.target != 0 && query.target != target) throw new IllegalArgumentException("Query target changed");
        query.target = target;
        return query;
    }

    private TimestampDriver driver() {
        if (driver == null) driver = factory.get();
        return driver;
    }

    private void release(Query query) {
        if (driver != null) {
            driver.release(query.start);
            driver.release(query.end);
        }
        query.start = 0;
        query.end = 0;
        query.result = null;
    }

    @Override
    public void close() {
        for (var query : queries.values()) release(query);
        queries.clear();
        active = 0;
        if (driver != null) {
            driver.close();
            driver = null;
        }
    }
}
