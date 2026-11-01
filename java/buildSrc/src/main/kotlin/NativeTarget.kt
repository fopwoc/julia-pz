import org.gradle.api.GradleException

/** A platform the one agent jar serves, as LWJGL's native classifier and its in-jar library directory. */
data class NativeTarget(val classifier: String, val os: String, val arch: String) {
    val directory get() = "$os/$arch"
    val sdlLibrary get() = when (os) {
        "macos" -> "libSDL3Julia.dylib"
        "windows" -> "SDL3Julia.dll"
        else -> "libSDL3Julia.so"
    }

    companion object {
        // Every platform PZ ships on. LWJGL's own natives are packed for all of them; the renderer's SDL build comes
        // from the host and from -PjuliaNatives=<dir> holding other hosts' builds as <os>/<arch>/<library>.
        val all = listOf(
            NativeTarget("natives-macos-arm64", "macos", "arm64"),
            NativeTarget("natives-macos", "macos", "x64"),
            NativeTarget("natives-linux", "linux", "x64"),
            NativeTarget("natives-windows", "windows", "x64"),
        )

        val host: NativeTarget by lazy {
            val arm = when (System.getProperty("os.arch")) {
                "aarch64", "arm64" -> true
                "amd64", "x86_64" -> false
                else -> throw GradleException("The graphics backend supports ARM64 and x86-64")
            }
            val os = System.getProperty("os.name").lowercase()
            when {
                os.startsWith("mac") -> all.first { it.os == "macos" && (it.arch == "arm64") == arm }
                os.startsWith("linux") && !arm -> all.first { it.os == "linux" }
                os.startsWith("windows") && !arm -> all.first { it.os == "windows" }
                else -> throw GradleException("No native target for $os on ${System.getProperty("os.arch")}")
            }
        }
    }
}
