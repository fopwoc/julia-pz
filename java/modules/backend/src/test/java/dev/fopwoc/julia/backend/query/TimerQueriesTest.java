package dev.fopwoc.julia.backend.query;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL33.*;

import java.util.ArrayList;
import java.util.HashMap;
import org.junit.jupiter.api.Test;

class TimerQueriesTest {
    @Test
    void namesAreReservedUntilFirstUseAndTimestampAvailabilityTracksTheGpu() {
        var driver = new Driver();
        try (var queries = new TimerQueries(() -> driver)) {
            int query = queries.generate();
            assertFalse(queries.isQuery(query));
            queries.timestamp(query, GL_TIMESTAMP);
            assertTrue(queries.isQuery(query));
            assertEquals(0, queries.get(query, GL_QUERY_RESULT_AVAILABLE));
            driver.available = true;
            assertEquals(1, queries.get(query, GL_QUERY_RESULT_AVAILABLE));
            assertEquals(100, queries.get(query, GL_QUERY_RESULT));
            assertEquals(100, queries.get(query, GL_QUERY_RESULT));
            assertEquals(1, driver.reads);
            queries.delete(query);
            assertFalse(queries.isQuery(query));
            assertEquals(java.util.List.of(1L), driver.released);
        }
    }

    @Test
    void elapsedQueriesUseTwoGpuSamplesAndRejectActiveResultsAndTargetChanges() {
        var driver = new Driver();
        try (var queries = new TimerQueries(() -> driver)) {
            int query = queries.generate();
            queries.begin(GL_TIME_ELAPSED, query);
            assertThrows(IllegalArgumentException.class, () -> queries.get(query, GL_QUERY_RESULT));
            assertThrows(IllegalStateException.class, () -> queries.begin(GL_TIME_ELAPSED, queries.generate()));
            queries.end(GL_TIME_ELAPSED);
            assertEquals(100, queries.get(query, GL_QUERY_RESULT));
            assertThrows(IllegalArgumentException.class, () -> queries.timestamp(query, GL_TIMESTAMP));
        }
        assertEquals(java.util.List.of(1L, 2L), driver.released);
        assertTrue(driver.closed);
    }

    @Test
    void reissuingQueriesReleasesTheOldGpuGenerationAndClearsCachedResults() {
        var driver = new Driver();
        try (var queries = new TimerQueries(() -> driver)) {
            int query = queries.generate();
            queries.timestamp(query, GL_TIMESTAMP);
            assertEquals(100, queries.get(query, GL_QUERY_RESULT));
            queries.timestamp(query, GL_TIMESTAMP);
            assertEquals(java.util.List.of(1L), driver.released);
            assertEquals(0, queries.get(query, GL_QUERY_RESULT_AVAILABLE));
            assertEquals(200, queries.get(query, GL_QUERY_RESULT));
        }
        assertEquals(java.util.List.of(1L, 2L), driver.released);
    }

    @Test
    void invalidAndUnsupportedOperationsCannotManufactureResults() {
        var driver = new Driver();
        try (var queries = new TimerQueries(() -> driver)) {
            int query = queries.generate();
            assertThrows(IllegalArgumentException.class, () -> queries.timestamp(999, GL_TIMESTAMP));
            assertThrows(IllegalArgumentException.class, () -> queries.timestamp(query, GL_SAMPLES_PASSED));
            assertThrows(UnsupportedOperationException.class, () -> queries.begin(GL_SAMPLES_PASSED, query));
            assertThrows(IllegalArgumentException.class, () -> queries.get(query, GL_QUERY_RESULT));
            assertThrows(IllegalStateException.class, () -> queries.end(GL_TIME_ELAPSED));
            queries.delete(0);
            queries.delete(999);
        }
        assertEquals(0, driver.counter);
    }

    private static final class Driver implements TimestampDriver {
        long counter;
        boolean available;
        boolean closed;
        int reads;
        final ArrayList<Long> released = new ArrayList<>();
        final HashMap<Long, Long> values = new HashMap<>();

        public long record() {
            long handle = ++counter;
            values.put(handle, handle * 100);
            return handle;
        }

        public boolean available(long sample) {
            return available;
        }

        public long result(long sample) {
            reads++;
            return values.get(sample);
        }

        public void release(long sample) {
            if (sample != 0) released.add(sample);
        }

        public void close() {
            closed = true;
        }
    }
}
