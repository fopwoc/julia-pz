// Workshop distribution: the Lua status companion plus the signed ZombieBuddy preload jar that does the rendering.
plugins {
    base
}

/** The repository root is the PZ mod folder; this Gradle build lives in its `java/` directory. */
val modRoot: Directory = rootProject.layout.projectDirectory.dir("..")

val preloadJar = project(":preload").tasks.named<Jar>("jar").flatMap { it.archiveFile }
val preloadSignature = project(":preload").tasks.named("signJar").map { it.outputs.files.singleFile }

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

// Direct uploads stage the jar and source manifest in the mod root; Git ignores both generated copies.
val stagePreload by tasks.registering {
    group = "distribution"
    description = "Copy the built preload jar into the mod root for a Workshop upload."
    val target = modRoot.file("libs/julia-preload.jar")
    val signature = preloadSignature
    val signatureTarget = modRoot.file("libs/julia-preload.jar.zbs")
    dependsOn("sourceManifest")
    inputs.file(sourceManifestFile)
    inputs.file(preloadJar)
    inputs.files(signature)
    outputs.files(target, modRoot.file("SOURCES.md"))
    doLast {
        preloadJar.get().asFile.copyTo(target.asFile, overwrite = true)
        // A stale signature would make ZombieBuddy reject the fresh jar, so an unsigned build removes it.
        signatureTarget.asFile.delete()
        signature.get().takeIf { it.isFile }?.copyTo(signatureTarget.asFile)
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
    from(preloadJar) { into("Julia/libs") }
    from(preloadSignature) { into("Julia/libs") }
}

tasks.assemble { dependsOn(modZip) }
