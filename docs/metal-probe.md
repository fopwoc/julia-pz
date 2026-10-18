# First Metal probe — 18 October 2026

## Verified result

The Julia ZombieBuddy mod ran inside the installed Project Zomboid 42.21.0 client on macOS. The user enabled and approved Julia, and ZombieBuddy invoked its Java entry point.

- Test process: `68610` (PZ's native `JavaAppLauncher`).
- Probe thread: `main`, reached through PZ's render-context queue; checked as the actual Cocoa main thread before initializing SDL video.
- Angelica libraries: published `sdl-gpu` / `glsm` 2.2.34, unmodified.
- LWJGL: 3.4.2 in a separate class loader, with its own native extraction directory.
- SDL runtime: 3.4.12.
- Backend/GPU: Metal / Apple M4 Max.
- Target: 32×32 RGBA8, cleared to `#20C060` with alpha 255.
- Readback: all 1,024 pixels matched exactly after GPU completion.
- Evidence: `result.txt`, `metal-clear.png`, the probe's status file, and the PZ console success line.
- Tested Julia.jar SHA-256: `bbafc14b2f9d5e7328f855b534e3dd0438295b9a9b0b11d3bd44a11d375d0aee`.

The same clear/readback also passed in a standalone JVM before the in-game test.

## Scope

This verifies real device creation, GPU texture allocation, a render-pass clear, GPU-to-CPU texture download, synchronization, and coexistence with PZ's currently loaded OpenGL/LWJGL runtime. The probe releases its textures, transfer buffer, fence, GPU device, and SDL subsystem after the check.

The game itself still uses its original OpenGL renderer. This does not yet verify GLSM initialization, OpenGL call redirection at runtime, shader compilation/translation, texture sampling, window presentation, or world rendering through Metal.

## Test profile

The game was launched with a separate cache directory, copied graphics options, and only ZombieBuddy plus Julia enabled. Normal saves and the normal mod list were not changed. PZ's first-run Build 42 reset initially emptied the new profile's mod list; the test profile was relaunched after that initialization completed. The user's normal game instance was closed by the user first.

No GUI automation or screenshots were used. The PNG is GPU readback data, not a screenshot. Subsequent visual/gameplay validation belongs to the user.
