import org.gradle.api.Project

/**
 * Build identity derived from Git, resolved once per build so every module reports the same version.
 *
 * `git describe --always --dirty` yields the nearest tag or the abbreviated commit, suffixed when the tree is dirty.
 */
class BuildInfo private constructor(val version: String) {
    companion object {
        @Volatile private var cached: BuildInfo? = null

        fun of(project: Project): BuildInfo =
            cached ?: synchronized(this) { cached ?: resolve(project.rootProject).also { cached = it } }

        private fun resolve(root: Project): BuildInfo {
            val described = root.providers.exec {
                commandLine("git", "describe", "--always", "--dirty")
                workingDir(root.rootDir)
                isIgnoreExitValue = true
            }.standardOutput.asText.get().trim()
            return BuildInfo(described.ifEmpty { "unversioned" })
        }
    }
}
