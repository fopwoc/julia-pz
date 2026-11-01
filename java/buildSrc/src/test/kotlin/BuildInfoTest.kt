import java.nio.file.Path
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class BuildInfoTest {
    @TempDir lateinit var directory: Path

    @Test
    fun reconstructsGitVersionFromExportedSourceIdentity() {
        val commit = "0123456789abcdef0123456789abcdef01234567"
        directory.resolve(".source-info.properties").toFile().writeText("commit=$commit\ntag=0.0.3\nbuildNumber=123\n")
        assertEquals(BuildInfo("0.0.3-01234567", true, 123, "01234567"), BuildInfo.fromArchive(directory.toFile()))
        directory.resolve(".source-info.properties").toFile().writeText("commit=$commit\ntag=\nbuildNumber=124\n")
        assertEquals(BuildInfo("01234567", false, 124, "01234567"), BuildInfo.fromArchive(directory.toFile()))
    }

    @Test
    fun rejectsMissingOrMalformedIdentity() {
        assertThrows(IllegalArgumentException::class.java) { BuildInfo.fromArchive(directory.toFile()) }
        for (contents in listOf("commit=bad\ntag=\nbuildNumber=1", "commit=${"a".repeat(40)}\ntag=latest\nbuildNumber=1",
                "commit=${"a".repeat(40)}\ntag=\nbuildNumber=-1")) {
            directory.resolve(".source-info.properties").toFile().writeText(contents)
            assertThrows(IllegalArgumentException::class.java) { BuildInfo.fromArchive(directory.toFile()) }
        }
    }
}
