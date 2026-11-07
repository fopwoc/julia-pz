import packaging.entryJar

// The third way to wire Julia in, for measurements only: observer, automation hooks and renderer in one jar, so both
// sides of a comparison launch the same agent. Nothing production depends on this, and it never ships to players.
plugins { id("julia.java-conventions") }

dependencies {
    implementation(project(":tooling:profiling:core"))
    implementation(project(":launch"))
    implementation(project(":utils"))
    runtimeOnly(project(":render-runtime"))
    runtimeOnly(project(":host"))
}

entryJar(
    archive = "profiling-agent.jar",
    exposed = listOf(":tooling:profiling:core", ":launch", ":utils", ":render-runtime"),
    attributes = mapOf(
        "Premain-Class" to "dev.fopwoc.julia.profiling.agent.ProfilerAgent",
        "Can-Retransform-Classes" to "true",
    ),
)

tasks.test {
    val archive = tasks.jar.flatMap { it.archiveFile }
    inputs.file(archive)
    doFirst { systemProperty("profiler.jar", archive.get().asFile.absolutePath) }
}
