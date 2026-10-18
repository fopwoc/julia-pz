# Uniform compatibility bridges — 18 October 2026

Julia now supplies three exact LWJGL 3 overloads absent from published GLSM 2.2.34:

- `glUniform1(int, float[])`: native float buffer upload, preserving all array elements.
- `glUniformMatrix4(int, boolean, float[])`: complete 16-float matrices and the transpose flag; incomplete trailing matrices are ignored, matching LWJGL's count calculation.
- `glGetActiveUniform(int, int, int, IntBuffer, IntBuffer)`: separate size/type outputs at their current positions and a string limited to `maxLength - 1` bytes.

The string bridge adapts two upstream SDL backend behaviors: reflection writes absolute output index zero and advances the name buffer. Temporary outputs keep caller positions and surrounding elements intact. The name capacity reserves the API's terminator space, and decoding begins at the original buffer position. Negative capacity and unusable output buffers fail before querying the backend.

`UniformBindings` substitutes only these exact signatures after upstream GLSM redirection. It also handles method handles in lambda arguments and nested dynamic constants. Already-supported buffer overloads and other calls retain their original targets. The controlled-scene loader uses this pass; the game remains untransformed.

## Verification

- 37 JVM tests pass, including exact-signature exclusion and method-handle transformations.
- The windowless native suite passes 3,072 pixel checks on Metal, including 1,024 through GLSM.
- The adapter shader actually consumes two uploaded float-array elements and a non-symmetric matrix uploaded with transpose enabled. Its three trailing float values must be ignored. Incorrect uploads change the pixel result.
- Reflection checks uniform array size/type, matrix type, caller buffer positions and sentinel elements, short names, and capacities zero and one.
- The raw-device baseline uses its original shader and still verifies 2,048 pixels separately.

An offline audit of the same vanilla archive changes 158 classes in memory. With Julia's bridges, 1,921 redirected references have a target signature and one remains missing (previously 1,917 present and five missing). The four resolved references use these three overloads. The remaining missing signature is `glTexImage3D(IIIIIIIIIJ)V`.

33 unchanged OpenGL references, 187 host-binding references, 45 host fields, and four window calls remain. Signature presence does not establish runtime compatibility. The first in-game window run exposed a frame-cache failure, corrected by the hosted-backend change below. PZ gameplay remains untransformed.

## Next boundary

Pointer-based texture uploads require correct pixel-unpack buffer offset and pixel-store semantics. Reinterpreting every long as a memory address would be wrong. That overload remains explicitly unresolved. After ABI coverage, live class redirection and persistent frame ownership still need implementation.

## Sources

- [LWJGL GL20 API](https://javadoc.lwjgl.org/org/lwjgl/opengl/GL20.html), checked through Context7 and official web documentation.
- [LWJGL generated GL20 implementation](https://github.com/LWJGL/lwjgl3/blob/master/modules/lwjgl/opengl/src/generated/java/org/lwjgl/opengl/GL20.java).
- Angelica source revision `ab3bf176967851bacb42ca4dae92d2ceda2e861e`, `GLStateManager` uniform methods and `SDLGPURenderBackend.getActiveUniform` / `writeUtf8`; native probes execute the published 2.2.34 binaries.

## Window retry and frame-cache correction

The first uniform-enabled window run (process 76964) returned magenta at pixel 0,0 instead of the texture. The user confirmed that PZ returned to a full-size menu with working input. A three-frame offscreen run initially passed, including at 3600×2204. Adding a GPU-idle wait between offscreen frames reproduced the same magenta failure at 32×32.

The published pipeline decides whether uniforms need pushing using dirty flags, the bound program, and native command-buffer pointer equality. SDL command-buffer state is local to each recording and submitted buffers become invalid. Native pointer reuse can therefore make an unchanged uniform appear already uploaded. This is the leading explanation from source inspection and the synchronous reproducer; native handle identities were not traced.

Julia's `HostedBackend.onFrameBegin` now invalidates the upstream state mirror before beginning a frame. That clears encoder-binding and uniform-push caches and lets upstream replay GL state. The published dependency stays unchanged. This deliberately broad correction is appropriate for the hosted probe; its cost and mid-frame command-buffer changes still need investigation before sustained gameplay integration.

The offscreen regression now draws three frames with a GPU-idle wait after each, without re-uploading uniforms. Its final 1,024 pixels pass with the correction; all 37 JVM tests also pass. Offscreen width/height are configurable using `julia.probe.width` and `julia.probe.height` JVM properties (default 32×32, positive dimensions, at most 16,777,216 pixels).

The corrected in-game retry (process 77384) passed:

- Three successful Metal swapchain submissions into the existing PZ window.
- 3600×2204 final framebuffer; all 7,934,400 pixels verified.
- Raw-device baseline: another 2,048 pixels verified.
- Cocoa state restored; original GL dispatch restored and verified by a real binding query.
- User confirmation: normal menu size and working mouse/keyboard input.

Installed JAR SHA-256: `5b513fd641ee0a856c71275ab585a442140d8c8ee2f1efa2c6488d7311eb998b`. The game remains open. This retry does not establish gameplay rendering on Metal.

[SDL command-buffer lifetime and local state](https://wiki.libsdl.org/SDL3/SDL_GPUCommandBuffer) documents the ownership boundary used by this correction.
