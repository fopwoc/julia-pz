package dev.fopwoc.julia.profiling.agent;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.classfile.ClassFile;
import org.junit.jupiter.api.Test;
import zombie.gameStates.GameStateMachine.StateAction;

class LoadingInstrumentationTest {
    @Test
    void continuesOnlyAfterOriginalLoadingChecksAllowIt() throws Exception {
        byte[] original;
        try (var input = getClass().getResourceAsStream("/zombie/gameStates/GameLoadingState.class")) {
            original = input.readAllBytes();
        }
        var passive = new zombie.gameStates.GameLoadingState();
        passive.assetsReady = true;
        assertEquals(StateAction.Remain, passive.update());
        byte[] transformed =
                LoadingInstrumentation.instrument(original, getClass().getClassLoader());
        assertTrue(ClassFile.of().verify(transformed).isEmpty());
        var type = new ClassLoader(getClass().getClassLoader()) {
            Class<?> define() {
                return defineClass(null, transformed, 0, transformed.length);
            }
        }.define();
        var state = type.getConstructor().newInstance();
        var update = type.getMethod("update");
        assertEquals(StateAction.Remain, update.invoke(state));
        type.getField("assetsReady").setBoolean(state, true);
        assertEquals(StateAction.Continue, update.invoke(state));
    }
}
