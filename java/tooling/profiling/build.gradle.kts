plugins { base }

val observer = configurations.create("observer") { isTransitive = false; isCanBeConsumed = false }
val core = configurations.create("coreLibrary") { isTransitive = false; isCanBeConsumed = false }
dependencies {
    add(observer.name, project(mapOf("path" to ":tooling:profiling:agent", "configuration" to "runtimeElements")))
    add(core.name, project(mapOf("path" to ":tooling:profiling:core", "configuration" to "runtimeElements")))
}

val toolingZip = tasks.register<Zip>("toolingZip") {
    archiveFileName = "profiling-tools.zip"
    destinationDirectory = layout.buildDirectory.dir("distributions")
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    into("profiling-tools") {
        from(projectDir) { include("profile.py", "runner/*.py", "README.md") }
        from(observer) { into("lib") }
        from(core) { into("lib") }
        from(rootProject.file("../LICENSE"))
        from(rootProject.file("../COPYRIGHT"))
    }
}

tasks.assemble { dependsOn(toolingZip) }
