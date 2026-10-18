package dev.fopwoc.julia.transform.prepare;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Set;
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

    @Test
    void requiresPreparedConsumersForHostHandoffs() {
        var frame = new ArchivePlan.Entry(
                "synthetic.jar",
                "test",
                new PreparedClass("Frame", new byte[] {1}, List.of(), 1, true, Set.of("imgui/gl3/ImGuiImplGl3")));
        var missing = new ArchivePlan(List.of(frame));
        assertTrue(missing.blockers().contains("MISSING_CONSUMER imgui/gl3/ImGuiImplGl3"));
        assertThrows(IllegalStateException.class, missing::requireBytecode);
        assertTrue(new ArchivePlan(List.of(frame, entry("imgui/gl3/ImGuiImplGl3", false, List.of()))).ready());
        assertFalse(new ArchivePlan(
                        List.of(frame, entry("imgui/gl3/ImGuiImplGl3", false, List.of("NATIVE_OPENGL example"))))
                .ready());
    }

    private static ArchivePlan.Entry entry(String name, boolean frames, List<String> blockers) {
        return new ArchivePlan.Entry(
                "synthetic.jar", "test", new PreparedClass(name, new byte[] {1}, blockers, 1, frames, Set.of()));
    }
}
