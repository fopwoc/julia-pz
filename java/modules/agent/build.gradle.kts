import java.nio.file.Files
import java.util.Base64
import packaging.compactRuntime
import signing.ZbsSignature

plugins {
    id("julia.java-conventions")
}

dependencies {
    implementation(project(":gl-audit"))
    implementation(project(":utils"))
    implementation(project(":render-runtime"))
    runtimeOnly(project(":host"))
}

val packagedRuntime = compactRuntime(configurations.runtimeClasspath.get(), sourceSets.main.get().output)

tasks.jar {
    archiveFileName = "julia-agent.jar"
    manifest {
        attributes("Premain-Class" to "dev.fopwoc.julia.agent.JuliaAgent", "ZB-Preload" to "true", "Implementation-Version" to project.version)
    }
    from(packagedRuntime) { into("lib") }
    // Only JDK-only bootstrap and auditing classes are exposed to the application loader.
    for (path in listOf(":gl-audit", ":utils", ":render-runtime")) {
        from(project(path).the<SourceSetContainer>()["main"].output)
    }
}

// CI passes the key as a secret; local release builds read the Git-ignored copy. Without one the jar stays unsigned,
// which still works as a plain -javaagent but not as a ZombieBuddy preload mod.
val signingKeyFile = rootProject.layout.projectDirectory.file("../signing/ed25519-private.der")
val signingKeySecret = providers.environmentVariable("ZBS_PRIVATE_KEY")
val steamId = providers.gradleProperty("zbsSteamID64")

val signJar by tasks.registering {
    group = "distribution"
    description = "Write the ZombieBuddy signature next to the agent jar when a signing key is available."
    val jar = tasks.jar.flatMap { it.archiveFile }
    val signature = layout.buildDirectory.file("libs/julia-agent.jar.zbs")
    inputs.file(jar)
    inputs.property("steamId", steamId)
    outputs.file(signature)
    // The key must stay out of Gradle's recorded task inputs, so this reruns instead of tracking it.
    outputs.upToDateWhen { false }
    doLast {
        val target = signature.get().asFile.toPath()
        val key = signingKeySecret.orNull?.takeIf { it.isNotBlank() }?.let { Base64.getMimeDecoder().decode(it) }
            ?: signingKeyFile.asFile.takeIf { it.isFile }?.readBytes()
        if (key == null) {
            Files.deleteIfExists(target)
            logger.lifecycle("No ZombieBuddy signing key: julia-agent.jar stays unsigned")
        } else {
            Files.writeString(target, ZbsSignature.render(jar.get().asFile.toPath(), steamId.get(), key))
        }
    }
}

tasks.register("generateSigningKey") {
    group = "distribution"
    description = "Create the ZombieBuddy signing key in the Git-ignored signing folder and print its public half."
    doLast {
        val published = ZbsSignature.generate(signingKeyFile.asFile.toPath())
        logger.lifecycle("Add to your Steam profile summary: {}", published)
    }
}

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

// Uses the ordinary agent layout; completeness is an additional distribution requirement.
tasks.register("universalJar") {
    group = "build"
    description = "Build the single agent JAR with every supported platform's native library."
    dependsOn(":sdl-gpu:verifyUniversalNatives", tasks.jar, signJar)
}
