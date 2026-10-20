package dev.fopwoc.julia.backend.query;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL30.GL_PRIMITIVES_GENERATED;
import static org.lwjgl.opengl.GL33.*;

import java.util.ArrayList;
import java.util.HashMap;
import org.junit.jupiter.api.Test;

class QueriesTest {
    @Test
    void namesAreReservedUntilFirstUseAndTimestampAvailabilityTracksTheGpu() {
        var driver = new Driver();
        try (var queries = new Queries(() -> driver, Occlusion::new)) {
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
        try (var queries = new Queries(() -> driver, Occlusion::new)) {
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
        try (var queries = new Queries(() -> driver, Occlusion::new)) {
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
        try (var queries = new Queries(() -> driver, Occlusion::new)) {
            int query = queries.generate();
            assertThrows(IllegalArgumentException.class, () -> queries.timestamp(999, GL_TIMESTAMP));
            assertThrows(IllegalArgumentException.class, () -> queries.timestamp(query, GL_SAMPLES_PASSED));
            assertThrows(UnsupportedOperationException.class, () -> queries.begin(GL_PRIMITIVES_GENERATED, query));
            assertThrows(IllegalArgumentException.class, () -> queries.get(query, GL_QUERY_RESULT));
            assertThrows(IllegalStateException.class, () -> queries.end(GL_TIME_ELAPSED));
            queries.delete(0);
            queries.delete(999);
        }
        assertEquals(0, driver.counter);
    }

    @Test
    void occlusionQueriesCountSamplesAlongsideTimersAndReduceAnySamplesToBooleans() {
        var occlusion = new Occlusion();
        try (var queries = new Queries(Driver::new, () -> occlusion)) {
            int samples = queries.generate(), any = queries.generate(), timer = queries.generate();
            queries.begin(GL_TIME_ELAPSED, timer);
            queries.begin(GL_SAMPLES_PASSED, samples);
            assertThrows(IllegalStateException.class, () -> queries.begin(GL_ANY_SAMPLES_PASSED, any));
            assertThrows(IllegalArgumentException.class, () -> queries.delete(samples));
            assertThrows(IllegalArgumentException.class, () -> queries.get(samples, GL_QUERY_RESULT));
            assertThrows(IllegalStateException.class, () -> queries.end(GL_ANY_SAMPLES_PASSED));
            queries.end(GL_SAMPLES_PASSED);
            queries.end(GL_TIME_ELAPSED);
            assertEquals(0, queries.get(samples, GL_QUERY_RESULT_AVAILABLE));
            occlusion.available = true;
            assertEquals(1, queries.get(samples, GL_QUERY_RESULT_AVAILABLE));
            assertEquals(1000, queries.get(samples, GL_QUERY_RESULT));
            queries.begin(GL_ANY_SAMPLES_PASSED, any);
            queries.end(GL_ANY_SAMPLES_PASSED);
            assertEquals(1, queries.get(any, GL_QUERY_RESULT));
            occlusion.counts.put(3L, 0L); // The reissued query takes the next native handle.
            queries.begin(GL_ANY_SAMPLES_PASSED, any);
            queries.end(GL_ANY_SAMPLES_PASSED);
            assertEquals(0, queries.get(any, GL_QUERY_RESULT));
            assertThrows(IllegalArgumentException.class, () -> queries.begin(GL_SAMPLES_PASSED, any));
        }
        // Reissuing releases the previous handle; closing releases the rest in name order.
        assertEquals(java.util.List.of(2L, 1L, 3L), occlusion.released);
        assertEquals(3, occlusion.ended);
        assertTrue(occlusion.closed);
    }

    private static final class Occlusion implements OcclusionDriver {
        long counter;
        int ended;
        boolean available;
        boolean closed;
        final ArrayList<Long> released = new ArrayList<>();
        final HashMap<Long, Long> counts = new HashMap<>();

        public long begin() {
            long handle = ++counter;
            counts.putIfAbsent(handle, handle * 1000);
            return handle;
        }

        public void end(long query) {
            ended++;
        }

        public boolean available(long query) {
            return available;
        }

        public long result(long query) {
            return counts.get(query);
        }

        public void release(long query) {
            if (query != 0) released.add(query);
        }

        public void close() {
            closed = true;
        }
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
