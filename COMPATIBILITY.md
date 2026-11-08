# Compatibility

Status legend: ✅ works · 🧪 experimental, gameplay testing pending · 🚧 planned

## Game versions

| Project Zomboid | Status |
| --- | --- |
| 42.21 | ✅ |
| 42.22 | ✅ |
| Other Build 42 releases | Untested |

Other versions may work too, especially with the standalone agent, which doesn't depend on ZombieBuddy's internals.
Julia rewrites game classes by name, so any game update can break it.

## Platforms

| OS | CPU | GPU API | Status |
| --- | --- | --- | --- |
| macOS | Apple silicon (arm64) | Metal | ✅ |
| macOS | Intel (x64) | Metal | 🧪 |
| Linux | x64 | Vulkan | ✅ |
| Windows | x64 | Vulkan | ✅ |

The jars carry the natives for every platform above and pick the right ones at startup.

- **ZombieBuddy mod** is tested on macOS and Linux; Windows results come from the standalone agent.
- **Linux** follows the game's GLFW backend. PZ uses X11 by default (XWayland on Wayland desktops), and that path is
  tested on a Steam Deck (RADV) and an RTX 4060 Ti PC (NVIDIA driver). Native Wayland is opt-in through
  `-Dzomboid.wayland=1` and probably works in windowed mode, but fractional scaling misplaces clicks in ZombieBuddy's
  overlay, and fullscreen leaves the game without a visible window.
- **Windows** is tested with Proton, not on native Windows. Reports from real Windows machines are very welcome.
- **Multiplayer** is untested, but Julia only changes how the game draws, nothing in gameplay, so it should be safe to
  use, especially as the standalone agent.

## Mods

Lua-only mods should all work: they draw through the game's own renderer, which Julia already covers. The mods to
watch are Java mods with their own OpenGL code, like the ones below.

| Mod | Version tested | Status | Notes |
| --- | --- | --- | --- |
| [ZombieBuddy](https://steamcommunity.com/sharedfiles/filedetails/?id=3619862853) | 2.3.4 | ✅ | Required for the Workshop item |
| [Viewpoint](https://steamcommunity.com/sharedfiles/filedetails/?id=3809306528) | alpha-0.1.6.1 | ✅ | All presets run, including high and extreme |
| [Grassier Grass](https://steamcommunity.com/sharedfiles/filedetails/?id=3818853354) | 0.1.4 | ✅ | |
| [WinterFX](https://steamcommunity.com/sharedfiles/filedetails/?id=3819972196) | | ✅ | Uses its mesh path, since tessellation isn't supported |
| Visceral | | ✅ | |
| [PZ3D](https://steamcommunity.com/sharedfiles/filedetails/?id=3807334881) | 0.9.16 | ❌ | Loads into the world, but walls are missing and other effects glitch. It needs geometry shaders, which SDL GPU lacks |

Got another mod running, or crashing? Open an issue.

## GL features by GPU API

Angelica translates core rendering for every API. Julia adds the GL features below through its own native SDL
extensions, implemented per API.

| Feature | Metal | Vulkan | Direct3D 12 |
| --- | --- | --- | --- |
| Core rendering: state, shaders, textures, framebuffers, draws | ✅ | ✅ | 🚧 |
| Buffer textures (`samplerBuffer`), including empty stores | ✅ | ✅ | 🚧 |
| Texture views (`glTextureView`, 2D and array) | ✅ | ✅ | 🚧 |
| Up to 32 sampled resources per graphics stage | ✅ | ✅ | 🚧 |
| Sparse draw buffers (`GL_NONE` color slots) | ✅ | ✅ | 🚧 |
| Full-width instance divisors and base instances | ✅ | ✅ | 🚧 |
| Timer queries (`GL_TIME_ELAPSED`, `GL_TIMESTAMP`) | ✅ | ✅ | 🚧 |
| Occlusion queries (`GL_SAMPLES_PASSED`, `GL_ANY_SAMPLES_PASSED`) | ✅ | ✅ | 🚧 |
| Fences (`glFenceSync`, `glClientWaitSync`) | ✅ | ✅ | 🚧 |

## Known limits

These GL features stop the game with a report rather than rendering incorrectly:

- Rendering to a whole array or 3D texture at once, and reading back or blitting framebuffers that target one slice.
- Texture views that change format, or views of cube and 3D textures.
- Buffer textures in formats other than R/RG/RGBA 32-bit, R16I/R16UI and RGBA16UI, and image load/store on buffer
  textures.
- More than 32 sampled resources in one graphics stage, or more than 16 in a compute shader.
- Uniform blocks, struct uniforms and atomic counters in uniform queries.
- Indirect draws from client memory pointers.
- Tessellation shaders and 1D textures. Tessellation stages fail to compile, as on hardware without them, so mods
  that check fall back.
- Integer colour clears to non-zero values, and shader storage ranges that start past offset 0.

SDL GPU has no border colour, so `GL_CLAMP_TO_BORDER` and legacy `GL_CLAMP` clamp to the edge texel instead. This is
the one approximation; Julia logs a warning the first time it happens.

Geometry shaders are not supported yet and are not caught early either: SDL GPU has no geometry stage and Angelica
links such programs without it. On Metal the first draw then fails pipeline creation; on Vulkan it may draw incorrectly.

## Policy

Julia follows the OpenGL specification and fails fast on anything it cannot reproduce exactly. One exception: when
code relies on a pattern that the specification forbids but every desktop driver accepts with one obvious meaning,
Julia emulates that meaning and logs a warning once. For example:

- Samplers of different types left on the same texture unit, behind branches that never read them. Each sampler reads
  its own target's binding on that unit, as desktop drivers do.
- An integer vertex input whose attribute array is disabled. Julia declares the matching integer format instead of
  float.
- A buffer texture attached to an empty buffer. It has width zero and reads as zero, as GL defines.

Anything ambiguous still stops the game with a report.
