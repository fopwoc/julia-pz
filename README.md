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
./gradlew check :gl-audit:installDist
modules/gl-audit/build/install/gl-audit/bin/gl-audit \
  --output build/reports/graphics.tsv /path/to/projectzomboid.jar /path/to/Viewpoint.jar
```

`gl-audit` reads JAR bytecode without loading game classes. It records direct method calls, field accesses, lambda implementation handles, and loaded method handles with exact JVM descriptors. Multi-release JARs use the running JDK's selected class variants. Embedded LWJGL/LWJGLX classes are excluded so binding implementation details do not inflate the inventory.

Reports contain archive SHA-256 identifiers and caller locations. They are static inventories, not proof that every referenced feature executes on macOS. Reflection, JNI calls, dynamically generated classes, and shader behavior require later investigation.

See [the first audit](docs/graphics-audit.md) for the measured game/mod surface.

Build identity comes from Git. macOS is the native development environment for the Metal integration. Tests and offline audits do not launch the game.
