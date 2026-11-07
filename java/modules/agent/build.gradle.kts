import packaging.entryJar

// Standalone wiring: a -javaagent jar for launches without ZombieBuddy, plus the graphics audit mode.
plugins {
    id("julia.java-conventions")
}

dependencies {
    implementation(project(":launch"))
    implementation(project(":gl-audit"))
    implementation(project(":utils"))
    implementation(project(":render-runtime"))
    runtimeOnly(project(":host"))
}

entryJar(
    archive = "julia-agent.jar",
    exposed = listOf(":launch", ":gl-audit", ":utils", ":render-runtime"),
    attributes = mapOf("Premain-Class" to "dev.fopwoc.julia.agent.JuliaAgent"),
)

tasks.test {
    val agentJar = tasks.jar.flatMap { it.archiveFile }
    inputs.file(agentJar).withPropertyName("agentJar")
    doFirst {
        systemProperty("agent.jar", agentJar.get().asFile.absolutePath)
        for (module in listOf("glsm", "sdl-gpu")) {
            val archive = project(":$module").tasks.named<Jar>("jar").get().archiveFile.get().asFile
            systemProperty("renderer.$module.jar", archive.absolutePath)
        }
    }
}
