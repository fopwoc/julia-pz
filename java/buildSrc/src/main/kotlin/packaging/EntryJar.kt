package packaging

import org.gradle.api.Project
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.bundling.Jar
import org.gradle.kotlin.dsl.get
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.named
import org.gradle.kotlin.dsl.register

/**
 * Lays out a jar for one way of wiring Julia into the game: the entry's own classes, the [exposed] JDK-only modules for
 * the application loader, and the isolated runtime nested under `lib/`.
 */
fun Project.entryJar(archive: String, exposed: List<String>, attributes: Map<String, String>) {
    val classes = extensions.getByType<SourceSetContainer>()["main"].output
    val packaged = compactRuntime(configurations["runtimeClasspath"], classes)
    val jar = tasks.named<Jar>("jar") {
        archiveFileName.set(archive)
        manifest.attributes(attributes + ("Implementation-Version" to project.version.toString()))
        from(packaged) { into("lib") }
        for (path in exposed) from(project(path).extensions.getByType<SourceSetContainer>()["main"].output)
    }
    // Uses the ordinary layout; completeness is an additional distribution requirement.
    tasks.register("universalJar") {
        group = "build"
        description = "Build $archive with every supported platform's native library."
        dependsOn(":sdl-gpu:verifyUniversalNatives", jar)
    }
}
