package dev.fopwoc.julia.backend.diagnostic;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class SamplerAssignmentTraceTest {
    @Test
    void distinguishesExplicitZeroFromNoAssignmentAndRetainsStateMismatch() {
        var history = new SamplerAssignmentTrace.History();
        history.initial.put("image", "location=3, map=-1, slot=0");
        assertTrue(history.describe().contains("completed writes=0"));
        history.assigned("image", 3, 0, 0, 0, "firstCaller");
        history.assigned("image", 3, 7, 0, 7, null);
        String report = history.describe();
        assertTrue(report.contains("image={writes=2, location=3, requested=7, stored=0, slot=7"));
        assertTrue(report.contains("first caller=firstCaller"));
        assertTrue(report.contains("#1 image{location=3, requested=0, stored=0, slot=0}"));
        assertTrue(report.contains("#2 image{location=3, requested=7, stored=0, slot=7}"));
    }

    @Test
    void coalescesRepeatedWritesWithoutLosingTheirCount() {
        var history = new SamplerAssignmentTrace.History();
        for (int i = 0; i < 10000; i++) history.assigned("image", 3, 7, 7, 7, "caller");
        String report = history.describe();
        assertTrue(report.contains("completed writes=10000"));
        assertTrue(report.contains("image={writes=10000"));
        assertTrue(report.contains("omitted changes=0"));
        assertFalse(report.contains("#2 "));
    }

    @Test
    void boundsChangesButPreservesFirstCallerAndLatestAssignment() {
        var history = new SamplerAssignmentTrace.History();
        for (int i = 0; i < 100; i++) history.assigned("image", 3, i, i, i, "firstCaller");
        String report = history.describe();
        assertTrue(report.contains("omitted changes=68"));
        assertTrue(report.contains("#69 image{"));
        assertTrue(report.contains("#100 image{location=3, requested=99, stored=99, slot=99}"));
        assertFalse(report.contains("#68 image{"));
        assertTrue(report.contains("first caller=firstCaller"));
    }

    @Test
    void retainsIgnoredLookupResultsAndBoundsLookupNames() {
        var history = new SamplerAssignmentTrace.History();
        history.lookup("inactive", -1);
        history.lookup("image", 3);
        history.lookup("image", 4);
        assertTrue(history.describe().contains("uniform lookups={image=4, inactive=-1}"));
        for (int i = 0; i < 200; i++) history.lookup("other" + i, i);
        assertTrue(history.describe().contains("omitted lookups=74"));
        history.lookup("image", 5);
        assertTrue(history.describe().contains("image=5"));
        assertFalse(history.describe().contains("other126="));
    }

    @Test
    void boundsSamplerEntriesAndReportsOmittedWrites() {
        var history = new SamplerAssignmentTrace.History();
        for (int i = 0; i < 200; i++) history.assigned("image" + i, i, 0, 0, 0, "caller");
        assertFalse(history.needsCaller("image128"));
        assertTrue(history.describe().contains("omitted writes=72"));
        assertFalse(history.describe().contains("image128="));
        history.assigned("image0", 0, 9, 9, 9, null);
        assertTrue(history.describe().contains("image0={writes=2, location=0, requested=9"));
    }
}
