import java.io.File
import org.gradle.api.Project

/**
 * Build identity derived from Git, resolved once per build so every module and artifact reports the same values.
 *
 * A commit tagged `1.2.3` builds as release version `1.2.3-<short hash>`, e.g. `1.2.3-5e098e5a`; any other commit
 * builds as `<short hash>`, e.g. `5e098e5a`. Uncommitted changes, including untracked files that
 * aren't ignored, append `-dirty`. The build number is the commit count at that revision.
 */
data class BuildInfo(val version: String, val isRelease: Boolean, val buildNumber: Int, val commitHash: String) {
    companion object {
        private val RELEASE = Regex("""\d+\.\d+\.\d+""")
        private const val KEY = "julia.buildInfo"

        // Cached on the root project, so it lives for one build only. A static cache would survive in the Gradle
        // daemon and label later builds with the commit that was checked out when the daemon started.
        fun of(project: Project): BuildInfo {
            val extra = project.rootProject.extensions.extraProperties
            return synchronized(extra) {
                if (!extra.has(KEY)) extra.set(KEY, project.resolve(project.rootDir.canonicalFile))
                extra.get(KEY) as BuildInfo
            }
        }

        private fun Project.resolve(repository: File): BuildInfo {
            val hash = git(repository, "rev-parse", "--short=8", "HEAD")
            val tag = git(repository, "tag", "--points-at", "HEAD", "--sort=-version:refname")
                .lineSequence()
                .firstOrNull(RELEASE::matches)
            val identity = if (tag == null) hash else "$tag-$hash"
            val dirty = if (git(repository, "status", "--porcelain").isEmpty()) "" else "-dirty"
            return BuildInfo("$identity$dirty", tag != null, git(repository, "rev-list", "--count", "HEAD").toInt(), hash)
        }

        private fun Project.git(repository: File, vararg arguments: String): String =
            providers.exec {
                workingDir(repository)
                commandLine("git", *arguments)
            }.standardOutput.asText.get().trim()
    }
}
