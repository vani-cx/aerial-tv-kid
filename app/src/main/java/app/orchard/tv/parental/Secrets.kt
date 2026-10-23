package app.orchard.tv.parental

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Password storage: PBKDF2-HMAC-SHA1, 60 000 rounds, a random 16-byte salt per password, stored as
 * "pbkdf2$<rounds>$<salt>$<hash>". Aerial's older PIN hashed a fixed salt with one SHA-256 round
 * (AerialSettings.hash), which a TV can afford to brute force; these two do not.
 *
 * 60 000 rounds is about a tenth of a second on a phone and a few tenths on a TV's CPU, so callers
 * run [encode] and [verify] off the main thread.
 */
internal object Secrets {
    private const val ROUNDS = 60_000
    private const val KEY_BITS = 256
    private const val SALT_BYTES = 16
    private const val PREFIX = "pbkdf2"

    /** Refuse a stored round count outside this: see [verify]. */
    private const val MIN_ROUNDS = 10_000
    private const val MAX_ROUNDS = 200_000

    fun encode(secret: String): String {
        val salt = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
        val hash = derive(secret.toCharArray(), salt, ROUNDS)
        return "$PREFIX\$$ROUNDS\$${b64(salt)}\$${b64(hash)}"
    }

    fun verify(secret: String, stored: String?): Boolean {
        if (stored.isNullOrBlank()) return false
        val parts = stored.split('$')
        if (parts.size != 4 || parts[0] != PREFIX) return false
        val rounds = parts[1].toIntOrNull() ?: return false
        // The round count travels with the stored string, so it is also a lever an attacker who can
        // write preferences could pull: 1 round to make guessing cheap, or a billion to hang the TV.
        if (rounds !in MIN_ROUNDS..MAX_ROUNDS) return false
        val salt = runCatching { Base64.decode(parts[2], Base64.NO_WRAP) }.getOrNull() ?: return false
        val want = runCatching { Base64.decode(parts[3], Base64.NO_WRAP) }.getOrNull() ?: return false
        val got = derive(secret.toCharArray(), salt, rounds)
        return MessageDigest.isEqual(want, got)
    }

    private fun derive(secret: CharArray, salt: ByteArray, rounds: Int): ByteArray {
        val spec = PBEKeySpec(secret, salt, rounds, KEY_BITS)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun b64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)
}
