rootProject.name = "julia-zomboid"

val rendererSources = file("../third_party/angelica")
require(rendererSources.resolve("glsm/src/main/java").isDirectory) {
    "Renderer submodule is missing. Run: git submodule update --init --recursive"
}

dependencyResolutionManagement {
    versionCatalogs {
        create("rendererLibs") { from(files(rendererSources.resolve("gradle/libs.versions.toml"))) }
    }
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
        maven("https://libraries.minecraft.net/") {
            content {
                includeGroup("com.mojang")
                includeGroup("net.minecraft")
                includeGroup("org.lwjgl.lwjgl")
            }
        }
        maven("https://nexus.gtnewhorizons.com/repository/public/") {
            content {
                includeGroup("com.gtnewhorizons.angelica")
                includeGroup("com.gtnewhorizons.retrofuturabootstrap")
                includeGroup("com.github.GTNewHorizons")
                includeGroup("org.taumc")
                includeGroup("org.embeddedt.celeritas")
                includeGroup("io.github.legacymoddingmc")
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
