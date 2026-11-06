package signing

import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.HexFormat
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ZbsSignatureTest {
    private val steamId = "76561198012345678"
    private val hex = HexFormat.of()

    @Test
    fun `sidecar verifies against the published key the way ZombieBuddy checks it`(@TempDir dir: Path) {
        val key = dir.resolve("signing/key.der")
        val published = ZbsSignature.generate(key).removePrefix("JavaModZBS:")
        val jar = Files.write(dir.resolve("mod.jar"), byteArrayOf(1, 2, 3))

        val lines = ZbsSignature.render(jar, steamId, Files.readAllBytes(key)).lines()

        assertEquals("ZBS", lines[0])
        assertEquals("SteamID64:$steamId", lines[1])
        val digest = hex.formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar)))
        val verifier = Signature.getInstance("Ed25519").apply {
            initVerify(publicKey(published))
            update("ZBS:$steamId:$digest".toByteArray())
        }
        assertTrue(verifier.verify(hex.parseHex(lines[2].removePrefix("Signature:"))))
    }

    @Test
    fun `never replaces an existing key`(@TempDir dir: Path) {
        val key = dir.resolve("key.der")
        ZbsSignature.generate(key)
        assertThrows(IllegalStateException::class.java) { ZbsSignature.generate(key) }
    }

    /** Wraps the raw 32 bytes in the fixed X.509 header for Ed25519, as a verifier reading the profile would. */
    private fun publicKey(raw: String) = KeyFactory.getInstance("Ed25519")
        .generatePublic(X509EncodedKeySpec(hex.parseHex("302a300506032b6570032100$raw")))
}
