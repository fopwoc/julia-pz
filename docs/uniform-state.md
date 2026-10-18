# Tracked uniform queries and integer uploads

Julia now routes ordinary uniform readbacks around the published SDL backend's empty `getUniformfv` / `getUniformiv` defaults. It reads Angelica's actual shader reflection, per-context `UniformStaging` values, and sampler/image-unit assignments. Julia does not maintain a second uniform-value store.

`ShaderStateAccess` contains the version-pinned private access: a VarHandle for `SDLGPURenderBackend.shaderManager` and a MethodHandle for its current-thread context accessor. These are resolved once and fail explicitly if the pinned upstream layout changes. The endpoint requires Julia's hosted SDL backend and the owning context thread.

The bridge covers the observed float/int scalar, FloatBuffer, float-array, and int-array read variants, plus the existing GLSM FloatBuffer/IntBuffer read overloads. Vector outputs preserve current positions and surrounding elements. Matrix results use upstream's column-major staged representation. Array queries return one element, as specified by OpenGL, rather than copying the entire uploaded array. Unknown/unlinked programs, invalid locations, missing reflected types, undersized/read-only outputs, and unsupported representations fail explicitly before output changes.

Integer arrays now upload through the real GLSM integer-buffer path. Signed integer and boolean scalar/array metadata is checked. A sampler/image scalar uses `glUniform1i`, preserving the backend's separate binding state instead of writing ordinary staging slots. Sampler/image arrays and overlong numeric uploads are rejected.

## Verified

- 69 JVM tests pass. New coverage includes raw integer bit patterns (including values not exactly representable as numeric floats), boolean normalization, output-buffer positions, defensive snapshots, matrix ordering, first-element array semantics, sampler state, and no partial writes on failed conversions.
- The windowless native suite still passes 3,072 pixels: 2,048 raw baseline plus 1,024 through typed isolated dispatch and the complete adapter.
- The actual adapter shader consumes uploaded integer-array elements and a boolean value. Readbacks verify those values, a vec2 from a nonzero buffer position, a transposed matrix, float-array first-element behavior, and the sampler assignment while the program is unbound.
- Three synchronously drained frames retain the command-buffer-reuse regression. Copied texture pixels still match exactly.
- Vanilla preflight: **175/182** graphics consumers structurally ready, **1,810** routed sites in ready classes, one frame anchor set, **8 unique blockers**, down from 14.
- No game launch, window, or live-mod replacement occurred. The startup agent remains audit-only.

## Explicit limits

This is bounded ordinary-uniform support, not complete OpenGL query/error compatibility. Invalid queries throw Java exceptions rather than synthesizing GL error flags. Non-integral/out-of-range float-to-integer conversions, unsigned values outside signed query range, external UBO reads, unreflected types, and unsupported upload shapes are rejected. Unwritten data uses upstream's zero staging default; Julia does not add GLSL initializer evaluation.

Indexed array-element locations remain limited by Angelica's location map. Full indexed-array lookup is not implemented. Some PZ helpers appear to expect whole-array readbacks; that requires later runtime validation, and Julia does not silently change the OpenGL query contract to satisfy that assumption.

Program-resource introspection is still blocked: the installed game queries buffer blocks/members as well as ordinary uniforms. Those APIs need their own interface-specific reflection support. Remaining static blockers are those three APIs, texture subregion clearing, texture views, named immutable buffer storage, debug callbacks, and host ImGui rendering. Persistent live frame ownership and further semantic gaps remain outside the blocker count.

## Sources

- [Khronos glGetUniform reference](https://github.com/KhronosGroup/OpenGL-Refpages/blob/main/gl4/glGetUniform.xml): component count, per-element array queries, and column-major matrix results.
- [LWJGL GL20 API](https://javadoc.lwjgl.org/org/lwjgl/opengl/GL20C.html), checked through Context7.
- Published Angelica 2.2.34 and source revision `ab3bf176967851bacb42ca4dae92d2ceda2e861e`: `ShaderManager.ProgramObject`, `UniformStaging`, `PipelineApplier`, and SDL sampler/image update paths.
