// Lua-only status companion for Workshop/manual installs; rendering lives in the separate startup agent.
plugins {
    base
}

/** The repository root is the PZ mod folder; this Gradle build lives in its `java/` directory. */
val modRoot: Directory = rootProject.layout.projectDirectory.dir("..")

val modZip by tasks.registering(Zip::class) {
    archiveFileName = "Julia.zip"
    destinationDirectory = layout.buildDirectory.dir("distributions")
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    from(modRoot) {
        include("common/**", "42/**", "licenses/**", "LICENSE", "COPYRIGHT", "THIRD_PARTY.md")
        into("Julia")
    }
}

tasks.assemble { dependsOn(modZip) }
