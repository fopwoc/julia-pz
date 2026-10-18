# Full graphics-adapter probe — 18 October 2026

## GPU result and post-probe crash

Julia initializes the real Angelica `GLStateManager` and `SDLGPURenderBackend`, then executes a controlled scene compiled against ordinary LWJGL OpenGL APIs. Angelica's published `GLSMRedirector` rewrites the scene before class definition. Julia rejects the scene if any native OpenGL reference remains.

The scene allocates an RGBA8 framebuffer and texture through redirected GL calls, compiles/links OpenGL GLSL 330, uploads sixteen distinct texels, sets a sampler uniform, draws a fullscreen triangle, and reads pixels through `glReadPixels`. All 1,024 pixels match the shader's red/blue-swapped pattern in GL's bottom-left coordinate system. A PNG is saved with rows flipped for display.

The adapter passes on Metal / Apple M4 Max. The complete native suite also passes the prior device baseline, giving 3,072 verified pixels across the two stages. Ordinary compiler/tests pass independently of native execution.

```sh
./gradlew :backend-probe:adapterProbe
./gradlew :backend-probe:suiteProbe
```

Results live under `modules/backend-probe/build/reports/adapter/` and `reports/suite/adapter/`. The ZombieBuddy JAR runs the same suite; its adapter evidence is under `<PZ cache>/julia/run-<pid>/adapter/`.

The same PZ process subsequently crashed at Angelica's dummy GL address when ordinary game rendering resumed. The GPU checks remain valid, but safe coexistence was not established. See [the host-dispatch correction](host-gl-crash.md); the fix is now JVM-tested and has passed a default-mode retest: host native GL queries work again, and the user reports that PZ reaches its menu.

## Host adaptation

Published artifacts remain intact on disk. Julia adapts narrowly scoped library bytecode in an isolated class loader:

- Angelica and GTNHLib legacy `org.lwjgl` references move to LWJGL3ify's `org.lwjglx` bridge. Classes declaring `@Lwjgl3Aware` retain modern bindings. PZ classes, LWJGL bindings, bridge classes, and Julia classes are excluded from this remapping.
- Minecraft's tessellator superclass reference becomes Julia's `TessellatorState`. This provides inherited state for GTNHLib's real `DirectTessellator`; it does not implement Minecraft rendering. Base drawing operations throw explicit exceptions. Minecraft terrain/tessellator integration and immediate-mode rendering are outside this probe's validated scope.
- A service-provider subclass selects the real SDL backend for an explicitly created offscreen device. It replaces the window-engagement availability check, with no private-field mutation. Rendering, resources, shaders, and readback remain upstream implementations.
- The bridge receives the real SDL backend's advertised capability set before GLSM initialization. The SDL presenter thread is disabled for this offscreen host. No display/window is claimed. The setting and thread context loader are restored after the isolated run.

Added runtime dependencies: LWJGL3ify 3.0.31 (`dev`), RetroFuturaBootstrap 1.1.0, Commons Lang 3.21.0, and LWJGL OpenGL 3.4.2 with the host native classifier. The bridge/bootstrap versions match the inspected Angelica dependency catalog. Julia does not invoke the Minecraft launcher or RFB event loop.

Source reference: LWJGL3ify revision `4b9a170e369cb718cbe06b9e4d2ff17fe142d4cf`, particularly `LwjglRedirectTransformer`, `GLContext`, and `ContextCapabilities`. The binary probe uses the actual published artifacts.

## Native ownership

The complete suite uses one adapted loader and one SDL native library. An earlier attempt used separate native loaders for the baseline and adapter: both stages passed, but macOS reported duplicate Objective-C classes. That arrangement was removed. The revised suite passes without those duplicate-class diagnostics.

Each stage releases its device before the next stage starts. The adapter writes its PASS result only after backend shutdown completes. The game retains its existing classes and native libraries, but the native dispatch slot on the calling thread is shared across class loaders. The new host guard explicitly restores it.

## Verification boundary

The updated ZombieBuddy mod completed its GPU checks inside Project Zomboid 42.21.0 after the user closed the previous instance and approved the changed JAR. The native launcher process was `70677`; both stages ran on the Cocoa `main` thread. The adapter reported `backend=SDL GPU (metal)`, `verified_pixels=1024`, and `api=redirected LWJGL OpenGL calls -> GLStateManager`. The baseline verified another 2,048 pixels, and the suite status file confirmed completion. No duplicate-SDL Objective-C diagnostics appeared in this run.

Tested build: `55d6549`. Julia.jar SHA-256: `de77043490baa7af4cc9ced6e98b592c76e550d801bab7444f99bbba4340164e`.

That build passed 22 tests. The separate native suite completed its GPU checks both standalone and inside the PZ process; the later native crash means successful continuation was not verified. The in-game PNG is GPU readback evidence, not a screen capture.

This is a controlled offscreen scene, not redirected PZ gameplay. Remaining work includes exact vanilla GL overloads and reachable passthrough calls, capability assumptions, PZ's window/input/event loop, presentation, thread/context ownership, and frame lifecycle. The existing audit agent remains observational. Viewpoint remains phase 2.

Regression tests cover namespace adaptation in descriptors, inheritance, class literals, and method handles; both annotation retention forms; excluded class namespaces; successful scene redirection; and rejection of partial/native passthrough.
