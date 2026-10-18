# Angelica integration — 18 October 2026

## Verified inputs

- Published libraries: `com.gtnewhorizons.angelica:sdl-gpu:2.2.34` and its `glsm:2.2.34` dependency.
- Source inspected: upstream revision `ab3bf176967851bacb42ca4dae92d2ceda2e861e`. This source snapshot is context; the binary probes below use the actual published 2.2.34 artifacts.
- Java 25, LWJGL 3.4.2, macOS ARM64 natives.
- Game/mod archive hashes are recorded in [the graphics inventory](graphics-audit.md).

## Published modules resolve

The modules resolve outside the Minecraft build. GLSM still brings GTNHLib and related libraries into its dependency graph. The publication includes Linux natives; Julia excludes those LWJGL dependencies and selects the current Unix host's ARM64 or x86-64 classifier explicitly.

ASM, JOML, fastutil, Guava, and logging are host-provided dependencies that must be supplied separately. The probe declares them explicitly.

## Headless linkage result

The probe loads classes without initializing them, then resolves their declared signatures. It never opens a window, loads native graphics libraries, or initializes a render backend.

| Entry point | Published dependencies plus ordinary host libraries | Also supplying Zomboid's JAR |
| --- | --- | --- |
| GLSMRedirector | Linked | Linked |
| GLStateManager | Missing `org.lwjgl.opengl.Drawable` | Missing `org.lwjgl.opengl.KHRDebugCallback` |
| SDLGPURenderBackend | Linked | Linked |
| SDLDrawable | Missing `org.lwjglx.opengl.DrawableGL` | Missing `org.lwjglx.opengl.DrawableGL` |

Each failure is the first type the JVM reports for that entry point; more can appear after it is resolved. A linked signature does not establish that a class can initialize or render successfully.

Source inspection also finds LWJGL3ify main-thread, event-loop, display, and capabilities hooks in the SDL backend. We must integrate these responsibilities with Zomboid's existing window/input loop. Whether that can use unchanged artifacts plus adapters remains open; a full Angelica fork is not established as necessary.

## Actual upstream redirector result

Julia ran the published `GLSMRedirector` on the original game/mod classes in memory, with its LWJGL3-aware mode enabled. Transformed game classes were never loaded or saved. It changed 285 classes across the two archives.

| References after transformation | Zomboid | Viewpoint |
| --- | ---: | ---: |
| Target public static signature present | 1,917 | 2,216 |
| Target signature missing | 5 | 37 |
| OpenGL call left unchanged | 33 | 164 |
| Host binding calls | 187 | 15 |
| Host window calls | 4 | 11 |
| Host field accesses | 45 | 23 |

These are reference counts, not executed-call counts or a compatibility percentage. Input filtering follows upstream `shouldTransform`. The target check only verifies the declared public static signature; it does not prove behavior or shader support.

Vanilla has four unique missing target signatures, all in `GLStateManager`:

```text
glGetActiveUniform(IIILjava/nio/IntBuffer;Ljava/nio/IntBuffer;)Ljava/lang/String;
glTexImage3D(IIIIIIIIIJ)V
glUniform1(I[F)V
glUniformMatrix4(IZ[F)V
```

There are 18 unique unchanged OpenGL method signatures in the game archive and 56 in Viewpoint. Viewpoint has 13 unique missing target signatures. Some references are platform- or feature-gated, so reachability needs separate investigation.

## Next implementation boundary

1. Resolve the graphics ABI expected by GLSM against the game: legacy callback/drawable types and LWJGL3ify ownership assumptions.
2. Adapt exact overloads and handle remaining reachable vanilla GL calls. Pointer-based texture uploads need correct pixel-unpack/buffer semantics.
3. Integrate SDL presentation with Zomboid's window, input, thread, and capability handling.
4. Validate initialization and offscreen rendering before a user-run vanilla gameplay check.
5. Address Viewpoint's additional surface in phase 2.

The `agent` module currently observes graphics references as scoped classes load. It does not redirect calls or select a new renderer.

## First native execution

The real Angelica `Device` now initializes on Metal inside the PZ process and passes a 1,024-pixel offscreen clear/readback check. See [the native probe evidence](metal-probe.md). The full `GLStateManager` / `SDLGPURenderBackend` initialization and ABI issues above remain unresolved.
