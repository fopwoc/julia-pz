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

/** Lua-only status companion for Workshop/manual installs; rendering lives in the separate startup agent. */
val modZip by tasks.registering(Zip::class) {
    archiveFileName = "Julia.zip"
    destinationDirectory = layout.buildDirectory.dir("distributions")
    from(modRoot) {
        include("common/**", "42/**", "licenses/**", "LICENSE", "COPYRIGHT", "THIRD_PARTY.md")
        exclude("42/media/java/**")
        into("Julia")
    }
}

tasks.assemble { dependsOn(modZip) }
