// The GTNHLib classes glsm uses, compiled from the fork nested in the renderer fork. The fork lists them itself.
plugins { `java-library` }

group = "dev.fopwoc.julia.renderer"
version = BuildInfo.of(project).version

val fork = rootProject.layout.projectDirectory.dir("../third_party/angelica/third_party/gtnhlib")
val sources = fork.file("pz-sources.txt").asFile.readLines().map(String::trim).filter { it.isNotEmpty() && !it.startsWith("#") }

java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
    withSourcesJar()
}

// The fork's own classes are always built; upstream files only as listed, so a replaced upstream class never clashes.
val upstreamSources = fileTree(fork.dir("src/main/java")) { include(sources) }
sourceSets.main {
    java.setSrcDirs(listOf(fork.dir("src/pz/java")))
    resources.setSrcDirs(emptyList<File>())
}
tasks.compileJava { source(upstreamSources) }
tasks.named<Jar>("sourcesJar") { from(upstreamSources) }

dependencies {
    api(libs.fastutil)
    api(libs.joml) { exclude(group = "org.jetbrains.kotlin") }
    implementation(libs.asm.tree)
    implementation(libs.log4j.api)
    implementation(libs.guava)
    implementation(libs.commons.lang3)
    compileOnly(rendererLibs.annotations)
    compileOnly(libs.jsr305)
    compileOnly(rendererLibs.lombok)
    annotationProcessor(rendererLibs.lombok)
    for (module in listOf("lwjgl", "lwjgl-opengl")) compileOnly("org.lwjgl:$module:${libs.versions.lwjgl.get()}")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = 25
    options.compilerArgs.addAll(listOf("-Xmaxerrs", "10000"))
}

tasks.withType<AbstractArchiveTask>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

tasks.withType<Jar>().configureEach {
    from(fork.file("LICENSE.txt")) { into("META-INF/licenses/gtnhlib") }
}
