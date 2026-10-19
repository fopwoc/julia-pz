package dev.fopwoc.julia.runtime;

/** Reports translation failures at the executed graphics call, independently of mod approval. */
public final class UnsupportedGraphics {
    private UnsupportedGraphics() {}

    public static UnsupportedOperationException failure(String source) {
        var failure = new UnsupportedOperationException("Julia cannot translate graphics operation: " + source);
        throw FatalGraphics.failure(failure.getMessage(), failure);
    }
}
