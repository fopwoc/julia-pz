package dev.fopwoc.julia.backend.diagnostic;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class TraceBudgetTest {
    @Test
    void deduplicatesPerProgramWithoutConsumingOtherProgramsQuota() {
        var budget = new TraceBudget(3, 1, 100);
        assertTrue(budget.reserve(1, "a", 10));
        assertFalse(budget.reserve(1, "a", 10));
        assertFalse(budget.reserve(1, "b", 10));
        assertTrue(budget.reserve(2, "a", 10));
        assertTrue(budget.reserve(3, "a", 10));
        assertFalse(budget.reserve(4, "a", 10));
    }

    @Test
    void enforcesEncodedByteLimitWithoutChargingRejectedOrRepeatedRecords() {
        var budget = new TraceBudget(10, 5, 20);
        assertTrue(budget.reserve(1, "a", 10));
        assertFalse(budget.reserve(1, "a", 10));
        assertFalse(budget.reserve(1, "b", 11));
        assertFalse(budget.reserve(1, "b", -1));
        assertTrue(budget.reserve(1, "b", 10));
        assertFalse(budget.available(2));
    }
}
