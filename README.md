# Julia

A Java compatibility project exploring Project Zomboid rendering through Angelica's graphics libraries and SDL-GPU, with Metal as the first target.

Julia is in its initial integration stage. It does not yet replace Zomboid's renderer.

## Plan

1. Run vanilla Zomboid through the shared graphics layer.
2. Extend compatibility for Viewpoint and other modern OpenGL mods.

The intended dependency chain is Julia → Angelica `sdl-gpu` / `glsm` → SDL3 → Metal. Published artifacts need verification outside Minecraft before we can rely on them unchanged.

## Development

Install a JDK 25. The Gradle wrapper pins Gradle 9.8.1 and verifies the distribution checksum.

```sh
./gradlew check
```

Build identity comes from Git. macOS is the native development environment for the Metal integration. Tests and offline audits do not launch the game.
