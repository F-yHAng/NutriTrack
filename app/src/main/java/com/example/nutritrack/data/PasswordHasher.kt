package com.example.nutritrack.data

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

data class PasswordCredential(
    val hash: ByteArray,
    val salt: ByteArray,
    val algorithm: String,
    val algorithmVersion: Int,
    val iterations: Int,
    val keyLengthBits: Int
)

class PasswordHasher internal constructor(
    private val secureRandom: SecureRandom = SecureRandom(),
    private val defaultIterations: Int = DEFAULT_ITERATIONS,
    private val defaultKeyLengthBits: Int = DEFAULT_KEY_LENGTH_BITS
) {
    init {
        require(defaultIterations in 1..MAX_SUPPORTED_ITERATIONS)
        require(defaultKeyLengthBits in MIN_KEY_LENGTH_BITS..MAX_KEY_LENGTH_BITS)
        require(defaultKeyLengthBits % 8 == 0)
    }

    fun hash(password: String): PasswordCredential {
        require(password.isNotEmpty()) { "Password must not be empty" }
        val salt = ByteArray(SALT_LENGTH_BYTES).also(secureRandom::nextBytes)
        return PasswordCredential(
            hash = derive(password, salt, defaultIterations, defaultKeyLengthBits),
            salt = salt,
            algorithm = ALGORITHM,
            algorithmVersion = ALGORITHM_VERSION,
            iterations = defaultIterations,
            keyLengthBits = defaultKeyLengthBits
        )
    }

    fun verify(password: String, account: UserAccount): Boolean {
        if (password.isEmpty() ||
            account.passwordAlgorithm != ALGORITHM ||
            account.passwordAlgorithmVersion != ALGORITHM_VERSION ||
            account.passwordIterations !in 1..MAX_SUPPORTED_ITERATIONS ||
            account.passwordKeyLengthBits !in MIN_KEY_LENGTH_BITS..MAX_KEY_LENGTH_BITS ||
            account.passwordKeyLengthBits % 8 != 0 ||
            account.passwordSalt.size < SALT_LENGTH_BYTES ||
            account.passwordHash.size != account.passwordKeyLengthBits / 8
        ) {
            return false
        }
        val candidate = derive(
            password = password,
            salt = account.passwordSalt,
            iterations = account.passwordIterations,
            keyLengthBits = account.passwordKeyLengthBits
        )
        return MessageDigest.isEqual(account.passwordHash, candidate)
    }

    private fun derive(
        password: String,
        salt: ByteArray,
        iterations: Int,
        keyLengthBits: Int
    ): ByteArray {
        val specification = PBEKeySpec(password.toCharArray(), salt, iterations, keyLengthBits)
        return try {
            SecretKeyFactory.getInstance(ALGORITHM).generateSecret(specification).encoded
        } finally {
            specification.clearPassword()
        }
    }

    companion object {
        const val ALGORITHM = "PBKDF2WithHmacSHA256"
        const val ALGORITHM_VERSION = 1
        const val DEFAULT_ITERATIONS = 600_000
        const val DEFAULT_KEY_LENGTH_BITS = 256
        const val SALT_LENGTH_BYTES = 16
        private const val MAX_SUPPORTED_ITERATIONS = 1_500_000
        private const val MIN_KEY_LENGTH_BITS = 128
        private const val MAX_KEY_LENGTH_BITS = 512
    }
}
