rootProject.name = "julia-zomboid"

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
        maven("https://libraries.minecraft.net/") {
            content {
                includeGroup("com.mojang")
                includeGroup("net.minecraft")
            }
        }
        maven("https://nexus.gtnewhorizons.com/repository/public/") {
            content {
                includeGroup("com.gtnewhorizons.angelica")
                includeGroup("com.gtnewhorizons.retrofuturabootstrap")
                includeGroup("com.github.GTNewHorizons")
                includeGroup("org.taumc")
                includeGroup("io.github.legacymoddingmc")
            }
        }
        maven("https://maven.minecraftforge.net/") {
            content { includeGroup("net.minecraftforge") }
        }
        maven("https://maven.taumc.org/releases/") {
            content { includeGroup("org.taumc") }
        }
    }
}

// Layers, bottom-up: utils → gl-audit → backend → transform → host → probe → agent; workshop packages the Lua companion.
// render-runtime is JDK-only and shared across the host/isolated loader boundary.
listOf(
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
