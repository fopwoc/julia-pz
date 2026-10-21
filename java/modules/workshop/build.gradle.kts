// Workshop/manual distribution: the Lua status companion plus the prebuilt startup agent that does the rendering.
plugins {
    base
}

/** The repository root is the PZ mod folder; this Gradle build lives in its `java/` directory. */
val modRoot: Directory = rootProject.layout.projectDirectory.dir("..")

val agentJar = project(":agent").tasks.named<Jar>("jar").flatMap { it.archiveFile }

// The Workshop upload takes the repository root as is, so the agent must sit there; Git ignores the copy.
val stageAgent by tasks.registering {
    group = "distribution"
    description = "Copy the built agent jar into the mod root for a Workshop upload."
    val target = modRoot.file("julia-agent.jar")
    inputs.file(agentJar)
    outputs.file(target)
    doLast { agentJar.get().asFile.copyTo(target.asFile, overwrite = true) }
}

val modZip by tasks.registering(Zip::class) {
    archiveFileName = "Julia.zip"
    destinationDirectory = layout.buildDirectory.dir("distributions")
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    from(modRoot) {
        include("common/**", "42/**", "licenses/**", "LICENSE", "COPYRIGHT", "THIRD_PARTY.md")
        into("Julia")
    }
    from(agentJar) { into("Julia") }
}

tasks.assemble { dependsOn(modZip) }
