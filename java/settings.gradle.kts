rootProject.name = "julia-zomboid"

val rendererSources = file("../third_party/angelica")
require(rendererSources.resolve("glsm/src/main/java").isDirectory
        && rendererSources.resolve("third_party/gtnhlib/pz-sources.txt").isFile) {
    "Renderer submodules are missing. Run: git submodule update --init --recursive"
}

dependencyResolutionManagement {
    versionCatalogs {
        create("rendererLibs") { from(files(rendererSources.resolve("gradle/libs.versions.toml"))) }
    }
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
        maven("https://nexus.gtnewhorizons.com/repository/public/") {
            content {
                includeGroup("org.taumc")
                includeGroup("org.embeddedt.celeritas")
            }
        }
        maven("https://maven.minecraftforge.net/") {
            content { includeGroup("net.minecraftforge") }
        }
        maven("https://maven.taumc.org/releases/") {
            content {
                includeGroup("org.taumc")
                includeGroup("org.embeddedt.celeritas")
            }
        }
    }
}

// Layers, bottom-up: utils → gl-audit → backend → transform → host → launch → agent or preload, the two
// ways of wiring Julia into the game; workshop packages the preload jar with the Lua companion.
// render-runtime is JDK-only and shared across the host/isolated loader boundary.
listOf(
    "gtnhlib",
    "glsm",
    "sdl-gpu",
    "renderer-tests",
    "utils",
    "gl-audit",
    "agent",
    "render-runtime",
    "backend",
    "transform",
    "host",
    "launch",
    "preload",
    "workshop",
).forEach { name ->
    include(name)
    project(":$name").projectDir = file("modules/$name")
}

project(":renderer-tests").projectDir = rendererSources.resolve("renderer-tests")

// Development executables are not dependencies of the production agent or Workshop runtime.
include(":probe")
project(":probe").projectDir = file("tooling/probe")
// tooling/profiling is a folder, not a project: its modules sit side by side like the rest.
include(":profiling-core", ":profiling-agent")
project(":profiling-core").projectDir = file("tooling/profiling/core")
project(":profiling-agent").projectDir = file("tooling/profiling/agent")
