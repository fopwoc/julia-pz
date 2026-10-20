package dev.fopwoc.julia.backend.query;

/** GPU sample counts for draws between begin and end; handles remain valid after their command buffers complete. */
public interface OcclusionDriver extends AutoCloseable {
    long begin();

    void end(long query);

    boolean available(long query);

    long result(long query);

    void release(long query);

    @Override
    void close();
}
