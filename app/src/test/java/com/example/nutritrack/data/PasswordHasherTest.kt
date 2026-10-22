package com.example.nutritrack.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PasswordHasherTest {
    private val hasher = PasswordHasher(defaultIterations = TEST_ITERATIONS)

    @Test
    fun samePassword_usesUniqueSaltAndHashForEachAccount() {
        val first = hasher.hash(PASSWORD)
        val second = hasher.hash(PASSWORD)

        assertFalse(first.salt.contentEquals(second.salt))
        assertFalse(first.hash.contentEquals(second.hash))
        assertTrue(first.iterations == TEST_ITERATIONS)
        assertTrue(first.algorithm == PasswordHasher.ALGORITHM)
    }

    @Test
    fun verify_acceptsCorrectPasswordAndRejectsIncorrectPassword() {
        val account = account(hasher.hash(PASSWORD))

        assertTrue(hasher.verify(PASSWORD, account))
        assertFalse(hasher.verify("Wrong1!", account))
    }

    @Test
    fun verify_rejectsUnsupportedOrMalformedParameters() {
        val credential = hasher.hash(PASSWORD)
        val account = account(credential).copy(passwordAlgorithmVersion = Int.MAX_VALUE)

        assertFalse(hasher.verify(PASSWORD, account))
    }

    private fun account(credential: PasswordCredential) = UserAccount(
        userId = 1,
        phoneNumber = "0400000000",
        name = "Test User",
        passwordHash = credential.hash,
        passwordSalt = credential.salt,
        passwordAlgorithm = credential.algorithm,
        passwordAlgorithmVersion = credential.algorithmVersion,
        passwordIterations = credential.iterations,
        passwordKeyLengthBits = credential.keyLengthBits
    )

    private companion object {
        const val PASSWORD = "Strong1!"
        const val TEST_ITERATIONS = 1_000
    }
}
