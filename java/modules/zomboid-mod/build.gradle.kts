plugins {
    id("julia.java-conventions")
}

/** The repository root is the PZ mod folder; this Gradle build lives in its `java/` directory. */
val modRoot: Directory = rootProject.layout.projectDirectory.dir("..")

configurations.runtimeClasspath {
    attributes { attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME)) }
}

dependencies {
    implementation(project(":utils"))
    implementation(project(":render-runtime"))
    runtimeOnly(project(":probe"))
}

tasks.jar {
    archiveFileName = "Julia.jar"
    manifest { attributes("Implementation-Version" to project.version) }
    // ZombieBuddy loads top-level classes only; the probe runtime stays nested and isolated under lib/.
    for (path in listOf(":utils", ":render-runtime")) {
        from(project(path).the<SourceSetContainer>()["main"].output)
    }
    from(configurations.runtimeClasspath) { into("lib") }
}

/** Puts the built JAR where `mod.info` expects it, so the repository folder is a runnable mod. */
val installModJar by tasks.registering(Copy::class) {
    from(tasks.jar)
    into(modRoot.dir("42/media/java/client"))
}

/** The installed mod folder, zipped as the Workshop sees it, for releases and manual installs. */
val modZip by tasks.registering(Zip::class) {
    dependsOn(installModJar)
    archiveFileName = "Julia.zip"
    destinationDirectory = layout.buildDirectory.dir("distributions")
    from(modRoot) {
        include("common/**", "42/**", "licenses/**", "LICENSE", "COPYRIGHT", "THIRD_PARTY.md")
        into("Julia")
    }
}

tasks.assemble { dependsOn(modZip) }
