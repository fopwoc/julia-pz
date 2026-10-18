plugins {
    id("julia.java-conventions")
}

dependencies {
    implementation(project(":gl-audit"))
    implementation(project(":utils"))
}

tasks.jar {
    archiveFileName = "julia-agent.jar"
    manifest {
        attributes("Premain-Class" to "dev.fopwoc.julia.agent.JuliaAgent", "Implementation-Version" to project.version)
    }
    // A -javaagent JAR must be self-contained.
    for (path in listOf(":gl-audit", ":utils")) {
        from(project(path).the<SourceSetContainer>()["main"].output)
    }
}

tasks.test {
    val agentJar = tasks.jar.flatMap { it.archiveFile }
    inputs.file(agentJar).withPropertyName("agentJar")
    doFirst { systemProperty("agent.jar", agentJar.get().asFile.absolutePath) }
}
