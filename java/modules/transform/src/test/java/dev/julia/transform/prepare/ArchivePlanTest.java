package dev.julia.transform.prepare;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class ArchivePlanTest {
    @Test
    void neverExposesPartiallySupportedInputs() {
        var plan = new ArchivePlan(
                List.of(entry("Frame", true, List.of()), entry("Broken", false, List.of("NATIVE_OPENGL unknown"))));
        assertFalse(plan.ready());
        assertThrows(IllegalStateException.class, plan::requireBytecode);
    }

    @Test
    void requiresExactlyOneFrameOwnerAndUniqueClassDefinitions() {
        assertFalse(new ArchivePlan(List.of(entry("Draw", false, List.of()))).ready());
        assertFalse(
                new ArchivePlan(List.of(entry("FrameA", true, List.of()), entry("FrameB", true, List.of()))).ready());
        var duplicate = new ArchivePlan(List.of(
                entry("Frame", true, List.of()), entry("Draw", false, List.of()), entry("Draw", false, List.of())));
        assertTrue(duplicate.blockers().contains("DUPLICATE_CLASS Draw"));
        assertFalse(duplicate.ready());
    }

    @Test
    void successfulPlanReturnsDefensiveBytecodeCopies() {
        var plan = new ArchivePlan(List.of(entry("Frame", true, List.of())));
        assertTrue(plan.ready());
        var first = plan.requireBytecode();
        first.get("Frame")[0] = 100;
        assertEquals(1, plan.requireBytecode().get("Frame")[0]);
        assertThrows(UnsupportedOperationException.class, () -> first.clear());
    }

    private static ArchivePlan.Entry entry(String name, boolean frames, List<String> blockers) {
        return new ArchivePlan.Entry(
                "synthetic.jar", "test", new PreparedClass(name, new byte[] {1}, blockers, 1, frames));
    }
}
