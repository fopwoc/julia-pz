// Workshop/manual distribution: the Lua status companion plus the prebuilt startup agent that does the rendering.
plugins {
    base
}

/** The repository root is the PZ mod folder; this Gradle build lives in its `java/` directory. */
val modRoot: Directory = rootProject.layout.projectDirectory.dir("..")

val agentJar = project(":agent").tasks.named<Jar>("jar").flatMap { it.archiveFile }
// PZ's Windows launcher cannot start -javaagent itself; the -agentlib loader from the native build does it instead.
val windowsLoader = providers.gradleProperty("juliaNatives").map { file(it).resolve("windows/x64/julia.dll") }

// The Workshop upload takes the repository root as is, so the agent must sit in it; Git ignores the copy. Like
// ZombieBuddy, the jar lives in libs/ so players find it next to the mod rather than mixed with its folders.
val stageAgent by tasks.registering {
    group = "distribution"
    description = "Copy the built agent jar into the mod root for a Workshop upload."
    val target = modRoot.file("libs/julia-agent.jar")
    val loaderTarget = modRoot.file("libs/julia.dll")
    val loader = windowsLoader.orNull
    inputs.file(agentJar)
    if (loader != null) inputs.file(loader)
    outputs.files(target, loaderTarget)
    doLast {
        agentJar.get().asFile.copyTo(target.asFile, overwrite = true)
        loader?.copyTo(loaderTarget.asFile, overwrite = true)
    }
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
    from(agentJar) { into("Julia/libs") }
    // Local builds have no Windows natives; CI passes them and the zip then carries the loader.
    from(windowsLoader.map { listOf(it) }.orElse(emptyList())) { into("Julia/libs") }
}

tasks.assemble { dependsOn(modZip) }
