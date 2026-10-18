# Metal probes — 18 October 2026

## First in-game clear result

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

The game itself still uses its original OpenGL renderer. This first clear result did not verify shader compilation or sampling; the textured-draw milestone below adds those checks. Full GLSM initialization, OpenGL call redirection at runtime, window presentation, and world rendering through Metal remain unverified.

## Test profile

The game was launched with a separate cache directory, copied graphics options, and only ZombieBuddy plus Julia enabled. Normal saves and the normal mod list were not changed. PZ's first-run Build 42 reset initially emptied the new profile's mod list; the test profile was relaunched after that initialization completed. The user's normal game instance was closed by the user first.

No GUI automation or screenshots were used. The PNG is GPU readback data, not a screenshot. Subsequent visual/gameplay validation belongs to the user.

## Translated textured draw

The next probe passed in a standalone JVM on Metal / Apple M4 Max, with the same unmodified Angelica 2.2.34 libraries and SDL 3.4.12. It exercises `ShaderManager.shaderSource`, compilation, linking, resource reflection/remapping, SPIR-V to MSL translation, and SDL Metal shader/pipeline creation using OpenGL GLSL 330 sources with `gl_VertexID`, a varying, and a `sampler2D`.

A 4×4 RGBA8 texture contains sixteen distinct, asymmetric colors. A fullscreen triangle samples it with nearest filtering into a 32×32 target, and the fragment shader swaps red and blue. All 1,024 downloaded pixels must match the transformed pattern in the correct orientation. The clear check also remains active, giving 2,048 verified pixels per run. Independent golden-color tests reject flipped rows, a single corrupted channel, and truncated readback. The ordinary build now passes 15 tests without initializing SDL or opening a window.

The first attempt exposed an upstream host dependency: shader linking calls Angelica's debug-environment check, which references Minecraft's `Launch.blackboard`. The isolated runtime now includes unmodified LaunchWrapper 1.12, with transitive dependencies disabled. No Minecraft launcher is invoked, and no Angelica implementation was patched.

The probe writes `metal-clear.png`, `metal-texture.png`, and `result.txt`; it removes stale proof files before starting. Resources are released through SDL's deferred-release APIs, and the shader manager is shut down before the device is destroyed. Angelica's worker pool uses daemon threads and its own idle shutdown; this is still a one-shot startup experiment.

In-game validation of this updated textured-draw build is pending a restart of the separate PZ test profile.
