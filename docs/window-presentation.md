# Borrowed-window presentation experiment — 18 October 2026

## Boundary

PZ's `org.lwjglx.opengl.Display` creates its window through GLFW, initializes ImGui's GLFW backend, and owns input callbacks. `RenderThread.lockStepRenderStep` renders queued sprite state, calls `Display.update(true)`, then checks controllers. `Display.update` swaps buffers and optionally processes messages; the render loop also processes messages while waiting.

The experiment preserves GLFW ownership and borrows the initialized native Cocoa window on the existing render/Cocoa thread. It creates an external SDL wrapper, lets the real Angelica backend claim it for Metal, presents three controlled translated frames, verifies final framebuffer pixels, then releases the device and wrapper. It does not replace the game's frame loop.

## Implementation

A narrow bytecode adapter replaces display-handle, size, and resize queries only in Angelica's `Device` and `SDLGPURenderBackend`. These use Julia's explicitly bound SDL window; a GLFW handle is never treated as an SDL handle. The service provider is now `HostedBackend`, supporting either an offscreen or explicitly borrowed-window device.

SDL's Cocoa listener changes responder links, touch acceptance, and window flags. Julia captures the native content view and delegate, both responder links, mouse-moved flag, full allowed-touch mask, released-when-closed flag, and one-shot flag. It restores and checks these after wrapping and after wrapper destruction. SDL removes its Metal child view when the GPU window is released. The host window binding rejects rebinding and cross-thread mutations.

The scene is compiled against ordinary LWJGL GL calls, then redirected by Angelica before loading. It requires exactly three increments of Angelica's successful-present counter, waits for GPU idle, and checks every final framebuffer pixel. The pattern oracle accounts for pixel centers on odd and non-square surfaces. A PNG is readback evidence, not a screenshot or proof that a compositor displayed it.

## Validation status

The implementation compiles; ownership, thread, remapping, and rectangular sampling regressions pass. Earlier standalone offscreen checks passed before the host-dispatch fix. The window experiment has never been run. The first priority is the [post-probe host GL restoration fix](host-gl-crash.md), whose runtime retest is also pending.

Window presentation is opt-in with `-Djulia.probe=window`; the default remains the offscreen adapter probe. No window test or game launch is performed by building or running ordinary JVM tests. The user will check that the ordinary menu and mouse/keyboard input return after detach when back at the computer.

This does not redirect PZ gameplay, bridge live frame boundaries, or validate interactive resize, fullscreen, minimize/restore, or input. Those remain subsequent work.

## Sources

- https://wiki.libsdl.org/SDL3/SDL_CreateWindowWithProperties
- https://wiki.libsdl.org/SDL3/SDL_ClaimWindowForGPUDevice
- https://wiki.libsdl.org/SDL3/SDL_WaitAndAcquireGPUSwapchainTexture
- https://github.com/libsdl-org/SDL/blob/release-3.4.12/src/video/cocoa/SDL_cocoawindow.m
- https://github.com/libsdl-org/SDL/blob/release-3.4.12/src/video/cocoa/SDL_cocoametalview.m
- https://www.glfw.org/docs/latest/group__native.html

AppKit selector signatures were checked against the installed SDK headers. No proprietary game source or binaries are committed.

## First native attempt and DPI correction

Build `bebc979` presented three frames in process `75920`, then rejected a readback pixel at `(0,545)`. The user reported a quarter-sized menu in the lower-left corner; ordinary gameplay still rendered with working controls. SDL's exact Cocoa source shows that foreign-window creation sets `wantsBestResolutionOpenGLSurface`, which was missing from the captured state. The snapshot now restores and verifies this flag too.

The reported readback boundary is consistent with a 2× Retina target: at height 2,182, row 545's center lies exactly on a quarter-texture boundary. The oracle now accepts either immediately adjacent texel only at exact mathematical boundaries, with exact channel checks everywhere. A regression test rejects non-adjacent colors and even one-bit channel corruption. This is not a broad color tolerance.

The corrected build passes 35 offline tests. The next native run will validate the DPI restoration and boundary handling.
