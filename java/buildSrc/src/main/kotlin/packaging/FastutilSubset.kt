package packaging

import java.io.File
import java.time.LocalDateTime
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.Nested
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.jvm.toolchain.JavaLauncher

/** Packages unchanged Fastutil classes reachable from every class in the supplied production runtime. */
@CacheableTask
abstract class FastutilSubset : DefaultTask() {
    @get:Classpath abstract val runtime: ConfigurableFileCollection
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val license: RegularFileProperty
    @get:Nested abstract val launcher: Property<JavaLauncher>
    @get:OutputFile abstract val archive: RegularFileProperty
    @get:OutputFile abstract val classList: RegularFileProperty

    @TaskAction
    fun subset() {
        val files = runtime.files.filter(File::exists).sortedBy(File::getName)
        val original = files.single { it.name.matches(Regex("fastutil-[0-9].*\\.jar")) }
        val seeds = FastutilClosure.references(analyse(files - original)).values.flatten().toSet()
        val dependencies = FastutilClosure.references(analyse(listOf(original)))
        ZipFile(original).use { source ->
            val entries = source.entries().asSequence().filterNot(ZipEntry::isDirectory).toList()
            val classes = entries.filter { it.name.endsWith(".class") }
                .map { it.name.removeSuffix(".class").replace('/', '.') }.toSet()
            val selected = FastutilClosure.select(classes, seeds, dependencies)
            val target = archive.get().asFile
            target.parentFile.mkdirs()
            ZipOutputStream(target.outputStream().buffered()).use { output ->
                output.setLevel(Deflater.BEST_COMPRESSION)
                for (entry in entries.sortedBy(ZipEntry::getName)) {
                    if (entry.name.endsWith(".class") && entry.name.removeSuffix(".class").replace('/', '.') !in selected) continue
                    val copy = ZipEntry(entry.name)
                    copy.setTimeLocal(LocalDateTime.of(1980, 1, 1, 0, 0))
                    output.putNextEntry(copy)
                    source.getInputStream(entry).use { it.copyTo(output) }
                    output.closeEntry()
                }
                val notice = ZipEntry("META-INF/LICENSE-fastutil.txt")
                notice.setTimeLocal(LocalDateTime.of(1980, 1, 1, 0, 0))
                output.putNextEntry(notice)
                license.get().asFile.inputStream().use { it.copyTo(output) }
                output.closeEntry()
            }
            classList.get().asFile.apply {
                parentFile.mkdirs()
                writeText(selected.joinToString("\n", postfix = "\n"))
            }
            logger.lifecycle("Fastutil: retained {} of {} classes ({} KiB)", selected.size, classes.size, target.length() / 1024)
        }
    }

    private fun analyse(files: List<File>): String {
        val java = launcher.get()
        val jdeps = java.metadata.installationPath.file("bin/jdeps").asFile
        val command = listOf(jdeps.path, "--multi-release", java.metadata.languageVersion.toString(),
            "--ignore-missing-deps", "-verbose:class", "-filter:none", "--regex", "it\\.unimi\\.dsi\\.fastutil\\..*") + files.map(File::getAbsolutePath)
        val process = ProcessBuilder(command).redirectErrorStream(true).apply { environment()["LC_ALL"] = "C" }.start()
        val report = process.inputStream.bufferedReader().use { it.readText() }
        check(process.waitFor() == 0) { "Fastutil dependency analysis failed:\n$report" }
        return report
    }
}
