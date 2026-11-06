package signing

import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.interfaces.EdECPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.util.HexFormat

/** ZombieBuddy's `.jar.zbs` sidecar: an Ed25519 signature over the signer's SteamID64 and the jar's SHA-256. */
object ZbsSignature {
    private const val ALGORITHM = "Ed25519"
    private val hex = HexFormat.of()

    fun render(jar: Path, steamId: String, privateKeyDer: ByteArray): String {
        require(steamId.matches(Regex("\\d{17}"))) { "SteamID64 must be 17 digits" }
        val digest = hex.formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar)))
        val key = KeyFactory.getInstance(ALGORITHM).generatePrivate(PKCS8EncodedKeySpec(privateKeyDer))
        val signature = Signature.getInstance(ALGORITHM).run {
            initSign(key)
            update("ZBS:$steamId:$digest".toByteArray())
            sign()
        }
        return "ZBS\nSteamID64:$steamId\nSignature:${hex.formatHex(signature)}\n"
    }

    /** Writes a new private key and returns the `JavaModZBS:` line its owner publishes on their Steam profile. */
    fun generate(privateKeyFile: Path): String {
        check(!Files.exists(privateKeyFile)) { "Refusing to replace signing key $privateKeyFile" }
        val pair = KeyPairGenerator.getInstance(ALGORITHM).generateKeyPair()
        Files.createDirectories(privateKeyFile.parent)
        Files.write(privateKeyFile, pair.private.encoded)
        return "JavaModZBS:${publicKeyHex(pair.public as EdECPublicKey)}"
    }

    // Java keeps the little-endian Y coordinate as a big integer with the X sign bit apart; RFC 8032 packs both together.
    internal fun publicKeyHex(key: EdECPublicKey): String {
        val littleEndian = key.point.y.toByteArray().reversedArray().copyOf(32)
        if (key.point.isXOdd) littleEndian[31] = (littleEndian[31].toInt() or 0x80).toByte()
        return hex.formatHex(littleEndian)
    }
}
