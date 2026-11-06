// Workshop/manual distribution: the Lua status companion plus the prebuilt startup agent that does the rendering.
plugins {
    base
}

/** The repository root is the PZ mod folder; this Gradle build lives in its `java/` directory. */
val modRoot: Directory = rootProject.layout.projectDirectory.dir("..")

val agentJar = project(":agent").tasks.named<Jar>("jar").flatMap { it.archiveFile }
val agentSignature = project(":agent").tasks.named("signJar").map { it.outputs.files.singleFile }
// PZ's Windows launcher cannot start -javaagent itself; the -agentlib loader from the native build does it instead.
val windowsLoader = providers.gradleProperty("juliaNatives").map { file(it).resolve("windows/x64/julia.dll") }

// The source ZIP expands the pinned forks; the small runtime ZIP points to those same revisions.
val hasGit = modRoot.file(".git").asFile.exists()
val sourceManifestFile = if (hasGit) layout.buildDirectory.file("source-manifest/SOURCES.md")
    else providers.provider { modRoot.file("SOURCES.md") }
val sourceManifest by tasks.registering(Exec::class) {
    group = "distribution"
    description = "Write exact Julia and renderer source revisions."
    inputs.property("revision", BuildInfo.of(project).version)
    inputs.dir(modRoot.dir("tools/distribution"))
    inputs.file(modRoot.file("tools/package-distribution.py"))
    outputs.file(sourceManifestFile)
    onlyIf { hasGit }
    commandLine("python3", modRoot.file("tools/package-distribution.py").asFile,
        "manifest", sourceManifestFile.get().asFile)
}

val sourceZip by tasks.registering(Exec::class) {
    group = "distribution"
    description = "Archive committed Julia and the required pinned renderer sources."
    val archive = layout.buildDirectory.file("distributions/Julia-sources.zip")
    inputs.property("revision", BuildInfo.of(project).version)
    inputs.dir(modRoot.dir("tools/distribution"))
    inputs.file(modRoot.file("tools/package-distribution.py"))
    outputs.file(archive)
    commandLine("python3", modRoot.file("tools/package-distribution.py").asFile, "sources", archive.get().asFile)
}

// Direct uploads stage the agent and source manifest in the mod root; Git ignores both generated copies.
val stageAgent by tasks.registering {
    group = "distribution"
    description = "Copy the built agent jar into the mod root for a Workshop upload."
    val target = modRoot.file("libs/julia-agent.jar")
    val signature = agentSignature
    val signatureTarget = modRoot.file("libs/julia-agent.jar.zbs")
    val loaderTarget = modRoot.file("libs/julia.dll")
    val loader = windowsLoader.orNull
    dependsOn("sourceManifest")
    inputs.file(sourceManifestFile)
    inputs.file(agentJar)
    inputs.files(signature)
    if (loader != null) inputs.file(loader)
    outputs.files(target, loaderTarget, modRoot.file("SOURCES.md"))
    doLast {
        agentJar.get().asFile.copyTo(target.asFile, overwrite = true)
        // A stale signature would make ZombieBuddy reject the fresh jar, so an unsigned build removes it.
        signatureTarget.asFile.delete()
        signature.get().takeIf { it.isFile }?.copyTo(signatureTarget.asFile)
        loader?.copyTo(loaderTarget.asFile, overwrite = true)
        val source = sourceManifestFile.get().asFile
        val destination = modRoot.file("SOURCES.md").asFile
        if (source != destination) source.copyTo(destination, overwrite = true)
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
    from(sourceManifestFile) { into("Julia") }
    dependsOn(sourceManifest)
    from(agentJar) { into("Julia/libs") }
    from(agentSignature) { into("Julia/libs") }
    // Local builds have no Windows natives; CI passes them and the zip then carries the loader.
    from(windowsLoader.map { listOf(it) }.orElse(emptyList())) { into("Julia/libs") }
}

tasks.assemble { dependsOn(modZip) }
