plugins {
    id("julia.java-conventions")
}

dependencies {
    implementation(project(":gl-audit"))
    implementation(project(":utils"))
    implementation(project(":render-runtime"))
    runtimeOnly(project(":host"))
}

tasks.jar {
    archiveFileName = "julia-agent.jar"
    manifest {
        attributes("Premain-Class" to "dev.fopwoc.julia.agent.JuliaAgent", "Implementation-Version" to project.version)
    }
    from(configurations.runtimeClasspath) { into("lib") }
    // Only JDK-only bootstrap and auditing classes are exposed to the application loader.
    for (path in listOf(":gl-audit", ":utils", ":render-runtime")) {
        from(project(path).the<SourceSetContainer>()["main"].output)
    }
}

tasks.test {
    val agentJar = tasks.jar.flatMap { it.archiveFile }
    inputs.file(agentJar).withPropertyName("agentJar")
    doFirst { systemProperty("agent.jar", agentJar.get().asFile.absolutePath) }
}
