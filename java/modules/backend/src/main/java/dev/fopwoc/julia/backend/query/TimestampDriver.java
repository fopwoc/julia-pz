package dev.fopwoc.julia.backend.query;

/** GPU-ordered timestamp samples; handles remain valid after their command buffers complete. */
public interface TimestampDriver extends AutoCloseable {
    long record();

    boolean available(long sample);

    long result(long sample);

    void release(long sample);

    @Override
    void close();
}
