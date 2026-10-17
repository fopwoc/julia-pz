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

## Modules

- `gl-audit`: offline graphics bytecode inventory and report writer.
- `agent`: startup Java agent; currently observes loaded `zombie/`, `viewpoint/`, and `imgui/` classes without rewriting them. Its report lists references in loaded classes, not calls proven to have executed. A separate-JVM smoke test verifies attachment and application behavior.
- `backend-probe`: consumes the published Angelica dependency, checks class linkage, and tests the upstream redirector in memory.

## Backend investigation

```sh
./gradlew :backend-probe:installDist
modules/backend-probe/build/install/backend-probe/bin/backend-probe /path/to/projectzomboid.jar
modules/backend-probe/build/install/backend-probe/bin/backend-probe \
  --audit-redirects build/reports/redirects.tsv /path/to/projectzomboid.jar /path/to/Viewpoint.jar
```

The linkage probe currently exits with status 1 because integration types are missing. This is a measured compatibility result, not a successful renderer startup. See [the integration findings](docs/angelica-integration.md).

## Audit agent

```sh
./gradlew :agent:jar
LOG_LEVEL=info java -javaagent:modules/agent/build/libs/julia-agent.jar=/path/to/new-report.tsv \
  -cp /path/to/application.jar example.Main
```

The report path is required and must not exist. Reports are written at normal JVM shutdown. The agent does not modify classes or choose a renderer. It has not been installed into or tested inside Zomboid. Default `LOG_LEVEL` is `error`; accepted overrides are `warn`, `info`, and `debug`. Agent logging only configures Julia's logger namespace.

Dependency versions are locked. Refresh deliberately with Gradle's `--write-locks` option. Angelica remains a replaceable external dependency; see [third-party notices](THIRD_PARTY.md).
