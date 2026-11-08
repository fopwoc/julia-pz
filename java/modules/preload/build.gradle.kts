import java.nio.file.Files
import java.util.Base64
import packaging.entryJar
import signing.ZbsSignature

// ZombieBuddy wiring: the signed preload mod jar that the Workshop item ships.
plugins {
    id("julia.java-conventions")
}

dependencies {
    implementation(project(":launch"))
    implementation(project(":utils"))
    implementation(project(":render-runtime"))
    runtimeOnly(project(":host"))
}

entryJar(
    archive = "julia-preload.jar",
    exposed = listOf(":launch", ":utils", ":render-runtime"),
    attributes = mapOf("ZB-Preload" to "true"),
)

// Local builds read the Git-ignored key; release CI signs with tools/sign-preload.sh instead, so keep both outputs
// identical. ZombieBuddy refuses an unsigned jar, so a build without a key is only good for compiling and tests.
val signingKeyFile = rootProject.layout.projectDirectory.file("../signing/ed25519-private.der")
val signingKeySecret = providers.environmentVariable("ZBS_PRIVATE_KEY")
val steamId = providers.gradleProperty("zbsSteamID64")

val signJar by tasks.registering {
    group = "distribution"
    description = "Write the ZombieBuddy signature next to the preload jar when a signing key is available."
    val jar = tasks.jar.flatMap { it.archiveFile }
    val signature = layout.buildDirectory.file("libs/julia-preload.jar.zbs")
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
            logger.lifecycle("No ZombieBuddy signing key: julia-preload.jar stays unsigned")
        } else {
            Files.writeString(target, ZbsSignature.render(jar.get().asFile.toPath(), steamId.get(), key))
        }
    }
}

tasks.named("universalJar") { dependsOn(signJar) }

tasks.register("generateSigningKey") {
    group = "distribution"
    description = "Create the ZombieBuddy signing key in the Git-ignored signing folder and print its public half."
    doLast {
        val published = ZbsSignature.generate(signingKeyFile.asFile.toPath())
        logger.lifecycle("Add to your Steam profile summary: {}", published)
    }
}

tasks.test {
    val preloadJar = tasks.jar.flatMap { it.archiveFile }
    inputs.file(preloadJar).withPropertyName("preloadJar")
    doFirst { systemProperty("preload.jar", preloadJar.get().asFile.absolutePath) }
}
