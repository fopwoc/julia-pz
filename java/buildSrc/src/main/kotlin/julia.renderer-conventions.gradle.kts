// Build the pinned renderer sources with Julia's toolchain, independently of Minecraft's build.
plugins { `java-library` }

group = "dev.fopwoc.julia.renderer"
version = BuildInfo.of(project).version

java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
    withSourcesJar()
}

val sources = rootProject.layout.projectDirectory.dir("../third_party/angelica/${project.name}")
sourceSets.main {
    java.setSrcDirs(listOf(sources.dir("src/main/java")))
    resources.setSrcDirs(listOf(sources.dir("src/main/resources")))
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = 25
}

tasks.withType<AbstractArchiveTask>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

tasks.withType<Jar>().configureEach {
    from(rootProject.layout.projectDirectory.file("../third_party/angelica/LICENSE")) { into("META-INF/licenses/angelica") }
}
