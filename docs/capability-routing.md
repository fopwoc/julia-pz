# Capability routing and vanilla aliases

Julia now prepares PZ's boolean capability reads against the SDL backend's advertised feature set. It covers both `GL.getCapabilities()` and reads of `Display.capabilities`. Native function-pointer tables never cross the isolated runtime boundary.

The transform replaces the local capability value with an immutable JDK `Set<String>` returned by `CapabilityBridge.current()`. Boolean extension/version fields become set membership queries, and class remapping updates stack-map/local-variable types. Native pointers, writes, unsupported capability properties, capability handles/literals, and objects escaping through declared fields or method signatures are rejected. This is a local-query bridge, not a general replacement for LWJGL's capabilities class.

These are **backend-advertised flags**, not verified implementation coverage. Angelica 2.2.34 advertises OpenGL versions through 4.6 despite implementation gaps. Julia retains whole-archive blockers for unsupported operations and adds an explicit rejection of `GLStateManager.glGetUniform` because this SDL backend inherits empty uniform-read implementations. Other semantic gaps may remain; passing structural checks never establishes complete support.

Six exact direct-call aliases now route to existing implemented endpoints:

- `GL11.glLightfv` → GLSM `glLight`.
- `GL11.glMaterialfv` → GLSM `glMaterial`.
- `GL11.glMultMatrixf` → GLSM `glMultMatrix`.
- `GL43.glClearDepthf` → GLSM `glClearDepth`, widening the operand from float to double.
- `GL43.glUniform2fv(FloatBuffer)` → GLSM `glUniform2`.
- `GL44.glCopyImageSubData` → GLSM `glCopyImageSubData`.

Unknown overloads and method handles are not guessed. Generated runtime method-reference helpers now preserve interface ownership metadata, verified by executing a synthetic interface lambda.

## Verification

- 62 JVM tests pass, including executing capability queries across branches, host Display-field reads, negative native-pointer/write/API escape checks, exact float widening, interface method references, and refusal of the empty uniform-read endpoint.
- The windowless Metal suite passes 3,072 pixel checks. Its adapter scene now checks actual advertised flags, depth-clear state after conversion, a vec2 upload from a nonzero FloatBuffer position, and texture copying before sampling the copied image. Three synchronously drained frames retain the earlier command-buffer reuse regression.
- Fixed-function light/material/matrix aliases are structurally routed; their full rendering behavior remains unverified.
- Vanilla archive preparation now reports 174/182 graphics consumers structurally ready, 1,783 routed call sites in ready classes, one frame anchor set, and **14 unique blockers**, down from 34. These are static counts, not a compatibility percentage.
- The game stayed closed. No live mod was installed and the startup agent remains audit-only.

## Remaining blockers

The 14 unique blockers cover the host ImGui renderer, an LWJGL-specific debug callback, three program-resource query APIs, five uniform-read variants, integer-array uniform upload, texture subregion clearing, texture views, and named immutable buffer storage. Persistent live frame ownership is also still to be wired; it is not included in that symbol count.

Next work should implement query/upload behavior against Angelica's actual tracked shader state, then address callbacks, ImGui, and remaining resource operations. Returning zeros from missing queries would hide incorrect shader state and is not accepted as support.

## Sources checked

- [LWJGL capability flags and function pointers](https://javadoc.lwjgl.org/org/lwjgl/opengl/GLCapabilities.html), also checked through Context7.
- [LWJGL current-thread capability API](https://javadoc.lwjgl.org/org/lwjgl/opengl/GL.html).
- [Khronos depth-clear semantics](https://wikis.khronos.org/opengl/GLAPI/glClearDepth).
- Angelica published 2.2.34 artifacts and inspected source revision `ab3bf176967851bacb42ca4dae92d2ceda2e861e`: advertised capability set, GLSM endpoints, SDL backend implementations, and the empty `RenderBackend.getUniformfv/getUniformiv` defaults.
