# Host GL dispatch restoration — 18 October 2026

## Crash evidence

The full-adapter run in PZ process `70677` completed its 3,072 pixel checks, then crashed immediately afterward. The JVM fatal-error log records `SIGBUS` at `0xdead000000000001`, while `org.lwjgl.opengl.GL11C.nglGenTextures` was called from PZ's texture creation path. That address is the exact sentinel installed by Angelica's `Lwjgl3GLCapabilitiesShim`.

The earlier PASS report proved the controlled GPU operations, but did not prove that the game continued running. The post-probe crash invalidates the earlier claim of safe coexistence. The installed artifact was build `55d6549`, SHA-256 `de77043490baa7af4cc9ced6e98b592c76e550d801bab7444f99bbba4340164e`.

## Cause and correction

LWJGL maintains Java capability objects per class loader, but its fast native dispatch uses reserved state in the current thread's `JNIEnv`. Independent class loaders and separate native extraction paths do not isolate that native thread state. The probe's fabricated GL capabilities overwrote the table used by PZ's next native GL call.

Julia now captures the game's capabilities through its own GL class before running the probe. On both success and failure it calls the game's `GL.setCapabilities(original)` on the original render thread, explicitly republishing the original native function table. The probe separately clears its fabricated capabilities and destroys its GL function provider, releasing/restoring the missing-function table managed by LWJGL.

A host `GL_TEXTURE_BINDING_2D` query is captured before the probe and repeated after restoration. Overall PASS and `host-gl-state.txt` are written only after that native query returns the same binding. A stage-level GPU result alone is not an overall survival result.

## Offline verification

The build passes 34 JVM tests. Four restoration tests use independent synthetic binding class loaders with distinct Java capability objects and a shared dispatch slot, reproducing the important condition: the host Java capability object still looks correct while native dispatch has changed. They verify restoration, exceptional exit, original-thread ownership, and rejection of a missing host context. Another test verifies clearing probe capabilities before destroying its provider. These tests load no real GL context or display.

The fix was built and JVM-tested during the code-only follow-up while the user was away. No game, display probe, or computer-use tool ran during that follow-up.

After the user returned and requested an in-game test, build `bebc979` passed the adapter suite in PZ process `75670`. `host-gl-state.txt` confirms that the original capabilities were republished and the native binding query matched the pre-probe value. The game remained alive past probe completion, and the user confirmed that the normal menu opened. The user also created a new game and confirmed working controls and ordinary world rendering.

Tested Julia.jar SHA-256: `9ec93dd60f2efd1b5a4f17af542f5775e049f8879aea9ae0be6c2fcd52f17e9b`.

## Probe modes

The default `julia.probe=adapter` runs the prior offscreen baseline/adapter sequence with the new restoration guard. The borrowed-window experiment is opt-in with `-Djulia.probe=window`. It subsequently passed a three-frame native retest with host restoration and user-confirmed normal menu sizing/input; see [the presentation evidence](window-presentation.md).

The default-mode retest established restoration and user-confirmed ordinary PZ world rendering after the probe. Gameplay, input, resizing, and presentation remain separate manual checks.

## Sources

- LWJGL GL thread-state contract and setter: https://github.com/LWJGL/lwjgl3/blob/master/modules/lwjgl/opengl/src/main/java/org/lwjgl/opengl/GL.java
- LWJGL JNIEnv dispatch storage: https://github.com/LWJGL/lwjgl3/blob/master/modules/lwjgl/core/src/main/java/org/lwjgl/system/ThreadLocalUtil.java
- Angelica sentinel capability implementation: https://github.com/GTNewHorizons/Angelica/blob/master/sdl-gpu/src/main/java/com/gtnewhorizons/angelica/sdlgpu/compat/Lwjgl3GLCapabilitiesShim.java

The installed game's own `GL.setCapabilities` bytecode was also inspected; it calls `ThreadLocalUtil.setCapabilities` directly. Full crash dumps, proprietary game code, and machine-specific paths are not committed.
