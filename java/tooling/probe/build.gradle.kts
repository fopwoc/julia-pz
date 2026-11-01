import packaging.compactRuntime

plugins {
    id("julia.java-conventions")
    application
}

dependencies {
    implementation(project(":utils"))
    implementation(project(":backend"))
    implementation(project(":transform"))
    implementation(project(":host"))
}

val packagedRuntime = compactRuntime(configurations.runtimeClasspath.get(), sourceSets.main.get().output)
sourceSets.main { runtimeClasspath = output + packagedRuntime }

application {
    mainClass = "dev.fopwoc.julia.probe.cli.ProbeMain"
    applicationName = "probe"
}

tasks.startScripts {
    classpath = files(tasks.jar, packagedRuntime)
}

distributions.main {
    contents {
        exclude { it.name.matches(Regex("fastutil-[0-9].*\\.jar")) }
        from(packagedRuntime.filter { it.name == "fastutil-subset.jar" }) { into("lib") }
        // Notices live in the mod folder at the repository root.
        from(rootProject.file("../THIRD_PARTY.md"))
        from(rootProject.file("../licenses")) { into("licenses") }
    }
}

/** Native probes open no window and run only when explicitly requested. */
data class NativeProbe(val task: String, val command: String, val report: String, val summary: String)

listOf(
    NativeProbe("renderProbe", "--render", "metal", "Runs the raw-device clear and translated textured-draw probe"),
    NativeProbe("adapterProbe", "--adapter", "adapter", "Runs redirected OpenGL draws through the offscreen GLSM adapter"),
    NativeProbe("suiteProbe", "--suite", "suite", "Runs the same native probe sequence as the game mod"),
).forEach { probe ->
    tasks.register<JavaExec>(probe.task) {
        group = "verification"
        description = probe.summary
        classpath = sourceSets.main.get().runtimeClasspath
        mainClass = application.mainClass
        javaLauncher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(25) }
        jvmArgs("--enable-native-access=ALL-UNNAMED")
        if (NativeTarget.host.os == "macos") jvmArgs("-XstartOnFirstThread")
        args(probe.command, layout.buildDirectory.dir("reports/${probe.report}").get().asFile.absolutePath)
    }
}
