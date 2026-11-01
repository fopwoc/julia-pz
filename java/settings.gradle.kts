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

// Layers, bottom-up: utils → gl-audit → backend → transform → host → probe → agent; workshop packages the Lua companion.
// render-runtime is JDK-only and shared across the host/isolated loader boundary.
listOf(
    "gtnhlib",
    "glsm",
    "sdl-gpu",
    "utils",
    "gl-audit",
    "agent",
    "render-runtime",
    "backend",
    "transform",
    "host",
    "probe",
    "workshop",
).forEach { name ->
    include(name)
    project(":$name").projectDir = file("modules/$name")
}
