package dev.fopwoc.julia.host;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class GameStartupTest {
    private static final String EXPOSER = "zombie.Lua.LuaManager$Exposer";
    private static final String RENDERER = "zombie.core.opengl.RenderThread";

    @Test
    void toleratesOnlyTheExposerAndWovenPatchClassesBeforePreload() {
        String patch = "me.zed_0xff.zombie_buddy.patches.Patch_MacOSRetina$Patch_nglfwCreateWindow";
        var candidates = Set.of(EXPOSER, RENDERER, patch);
        assertEquals(List.of(), GameStartup.loadedConsumers(candidates, Stream.of(EXPOSER, patch, "java.lang.String")));
        assertEquals(List.of(RENDERER), GameStartup.loadedConsumers(candidates, Stream.of(EXPOSER, patch, RENDERER)));
    }

    @Test
    void loadsPreparedClassesWithoutInitializingThemAndSkipsMissingOnes() {
        GameStartup.loadPrepared(
                List.of("does.not.Exist", LoadOnly.class.getName()), getClass().getClassLoader());
        assertFalse(loadOnlyInitialized);
    }

    private static boolean loadOnlyInitialized;

    private static final class LoadOnly {
        static {
            loadOnlyInitialized = true;
        }
    }
}
