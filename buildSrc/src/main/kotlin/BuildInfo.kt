import java.io.File
import org.gradle.api.Project

/**
 * Build identity derived from Git, resolved once per build so every module and artifact reports the same values.
 *
 * A commit tagged `1.2.3` builds as version `1.2.3`; any other commit builds as `<commit date>-<short hash>`,
 * e.g. `20261018-5e098e5a`. The build number is the commit count at that revision.
 */
data class BuildInfo(val version: String, val buildNumber: Int, val commitHash: String) {
    val isRelease: Boolean = RELEASE.matches(version)

    companion object {
        private val RELEASE = Regex("""\d+\.\d+\.\d+""")
        private val resolved = mutableMapOf<File, BuildInfo>()

        fun of(project: Project): BuildInfo = synchronized(resolved) {
            val repository = project.rootDir.canonicalFile
            resolved.getOrPut(repository) { project.resolve(repository) }
        }

        private fun Project.resolve(repository: File): BuildInfo {
            val hash = git(repository, "rev-parse", "--short=8", "HEAD")
            val tag = git(repository, "tag", "--points-at", "HEAD", "--sort=-version:refname")
                .lineSequence()
                .firstOrNull(RELEASE::matches)
            val version = tag ?: "${git(repository, "show", "-s", "--format=%cs", "HEAD").replace("-", "")}-$hash"
            return BuildInfo(version, git(repository, "rev-list", "--count", "HEAD").toInt(), hash)
        }

        private fun Project.git(repository: File, vararg arguments: String): String =
            providers.exec {
                workingDir(repository)
                commandLine("git", *arguments)
            }.standardOutput.asText.get().trim()
    }
}
