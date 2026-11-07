package dev.fopwoc.julia.profiling.agent;

/** One line of the status panel. Adding a number to the panel is one more of these. */
record Row(String label, String value) {
    /** A blank line between groups. */
    static final Row GAP = new Row("", "");
}
