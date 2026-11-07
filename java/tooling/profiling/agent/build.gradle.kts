import packaging.entryJar

// The third way to wire Julia in, for measurements only: observer, automation hooks and renderer in one jar, so both
// sides of a comparison launch the same agent. Nothing production depends on this, and it never ships to players.
plugins { id("julia.java-conventions") }

dependencies {
    implementation(project(":profiling-core"))
    implementation(project(":launch"))
    implementation(project(":utils"))
    runtimeOnly(project(":render-runtime"))
    runtimeOnly(project(":host"))
}

entryJar(
    archive = "profiling-agent.jar",
    exposed = listOf(":profiling-core", ":launch", ":utils", ":render-runtime"),
    attributes = mapOf(
        "Premain-Class" to "dev.fopwoc.julia.profiling.agent.ProfilerAgent",
        "Can-Retransform-Classes" to "true",
        "Main-Class" to "dev.fopwoc.julia.profiling.run.ProfileCli",
    ),
)

tasks.test {
    val archive = tasks.jar.flatMap { it.archiveFile }
    inputs.file(archive)
    doFirst { systemProperty("profiler.jar", archive.get().asFile.absolutePath) }
}

// A portable copy of the tool: the jar already holds everything it runs.
val toolingZip = tasks.register<Zip>("toolingZip") {
    archiveFileName = "profiling-tools.zip"
    destinationDirectory = layout.buildDirectory.dir("distributions")
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    into("profiling-tools") {
        from(projectDir.parentFile) { include("profile.sh", "profile.ps1", "scenes/*.json", "README.md") }
        from(tasks.jar) { into("lib") }
        from(rootProject.file("../LICENSE"))
        from(rootProject.file("../COPYRIGHT"))
    }
}

tasks.assemble { dependsOn(toolingZip) }
