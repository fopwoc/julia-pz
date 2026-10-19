package dev.fopwoc.julia.runtime;

/** Reports translation failures at the executed graphics call, independently of mod approval. */
public final class UnsupportedGraphics {
    private static final System.Logger LOG = System.getLogger(UnsupportedGraphics.class.getName());

    private UnsupportedGraphics() {}

    public static UnsupportedOperationException failure(String source) {
        var failure = new UnsupportedOperationException("Julia cannot translate graphics operation: " + source);
        LOG.log(System.Logger.Level.ERROR, failure.getMessage(), failure);
        return failure;
    }
}
