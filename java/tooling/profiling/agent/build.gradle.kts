plugins { id("julia.java-conventions") }

dependencies {
    implementation(project(":tooling:profiling:core"))
    implementation(project(":utils"))
}

tasks.jar {
    dependsOn(configurations.runtimeClasspath)
    archiveFileName = "profiling-agent.jar"
    manifest {
        attributes("Premain-Class" to "dev.fopwoc.julia.profiling.agent.ProfilerAgent",
            "Can-Retransform-Classes" to "true", "Implementation-Version" to project.version)
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(configurations.runtimeClasspath.map { files -> files.map { zipTree(it) } })
}

tasks.test {
    val archive = tasks.jar.flatMap { it.archiveFile }
    inputs.file(archive)
    doFirst { systemProperty("profiler.jar", archive.get().asFile.absolutePath) }
}
