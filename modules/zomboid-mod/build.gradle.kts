plugins {
    id("julia.java-conventions")
}

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

val modZip by tasks.registering(Zip::class) {
    archiveFileName = "Julia.zip"
    destinationDirectory = layout.buildDirectory.dir("distributions")
    from("src/mod")
    from(tasks.jar) { into("Julia/42/media/java/client") }
    from(rootProject.file("THIRD_PARTY.md")) { into("Julia") }
    from(rootProject.file("licenses")) { into("Julia/licenses") }
}

tasks.assemble { dependsOn(modZip) }
