package packaging

import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.file.FileCollection
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.register

/** Only distributions and native probes use the reduced artifact; compilation keeps the full published API. */
fun Project.compactRuntime(dependencies: Configuration, classes: FileCollection): FileCollection {
    val java = extensions.getByType<JavaToolchainService>().launcherFor {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
    val subset = tasks.register<FastutilSubset>("subsetFastutil") {
        runtime.from(dependencies, classes)
        launcher.set(java)
        license.set(rootProject.layout.projectDirectory.file("../licenses/Apache-2.0.txt"))
        archive.set(layout.buildDirectory.file("runtime/fastutil-subset.jar"))
        classList.set(layout.buildDirectory.file("reports/fastutil/classes.txt"))
    }
    return files(dependencies.filter { !it.name.matches(Regex("fastutil-[0-9].*\\.jar")) }, subset.flatMap { it.archive })
}
