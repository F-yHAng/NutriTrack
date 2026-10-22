package com.example.nutritrack.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AuthViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun initialLoad_exposesSortedAvailableAndUnregisteredUsers() = runTest {
        val dataSource = FakeAuthDataSource().apply {
            userIds = listOf(3, 1, 2, 2)
            registeredIds += 2
            accounts[2] = account(2)
        }

        val viewModel = AuthViewModel(dataSource, FakeAuthSessionDataSource())
        advanceUntilIdle()

        assertEquals(listOf(1, 2, 3), viewModel.uiState.value.availableUserIds)
        assertEquals(listOf(1, 3), viewModel.uiState.value.unregisteredUserIds)
        assertFalse(viewModel.uiState.value.isLoadingUsers)
    }

    @Test
    fun emptyInputs_areRejectedBeforeRepositoryCall() = runTest {
        val dataSource = FakeAuthDataSource()
        val viewModel = AuthViewModel(dataSource, FakeAuthSessionDataSource())
        advanceUntilIdle()

        viewModel.login("")
        assertEquals("Please select a User ID", viewModel.uiState.value.errorMessage)
        assertEquals(0, dataSource.loginCalls)

        viewModel.showRegistration()
        viewModel.register("", "", "", "")
        assertEquals("Please select a User ID", viewModel.uiState.value.errorMessage)
        assertEquals(0, dataSource.registerCalls)
    }

    @Test
    fun wrongCredentials_exposeErrorAndDoNotCreateSession() = runTest {
        val dataSource = FakeAuthDataSource().apply {
            userIds = listOf(USER_ID)
            registeredIds += USER_ID
            accounts[USER_ID] = account(USER_ID)
        }
        val session = FakeAuthSessionDataSource()
        val viewModel = AuthViewModel(dataSource, session)
        advanceUntilIdle()
        viewModel.selectUser(USER_ID)
        advanceUntilIdle()

        viewModel.login("wrong")
        advanceUntilIdle()

        assertEquals("Invalid password. Please try again.", viewModel.uiState.value.errorMessage)
        assertNull(session.savedUserId)
        assertNull(viewModel.uiState.value.navigation)
    }

    @Test
    fun loginSuccess_savesSessionAndResolvesQuestionnaireDestination() = runTest {
        val dataSource = FakeAuthDataSource().apply {
            userIds = listOf(USER_ID)
            registeredIds += USER_ID
            accounts[USER_ID] = account(USER_ID)
            completedQuestionnaire = true
        }
        val session = FakeAuthSessionDataSource()
        val viewModel = AuthViewModel(dataSource, session)
        advanceUntilIdle()
        viewModel.selectUser(USER_ID)
        advanceUntilIdle()

        viewModel.login(PASSWORD)
        advanceUntilIdle()

        assertEquals(USER_ID, session.savedUserId)
        assertTrue(viewModel.uiState.value.navigation?.hasCompletedQuestionnaire == true)
        assertEquals(USER_ID, viewModel.uiState.value.navigation?.userId)
    }

    @Test
    fun registrationSuccess_savesAccountSessionAndQuestionnaireDestination() = runTest {
        val dataSource = FakeAuthDataSource().apply {
            userIds = listOf(USER_ID)
            validIdentity = true
        }
        val session = FakeAuthSessionDataSource()
        val viewModel = AuthViewModel(dataSource, session)
        advanceUntilIdle()
        viewModel.showRegistration()
        viewModel.selectUser(USER_ID)
        viewModel.validateRegistrationCredentials(PHONE)
        advanceUntilIdle()

        viewModel.register(PHONE, "Test User", PASSWORD, PASSWORD)
        advanceUntilIdle()

        assertEquals(1, dataSource.registerCalls)
        assertEquals(USER_ID, session.savedUserId)
        assertFalse(viewModel.uiState.value.navigation?.hasCompletedQuestionnaire ?: true)
    }

    @Test
    fun duplicateAccountDetectedAtSubmit_isNotOverwritten() = runTest {
        val dataSource = FakeAuthDataSource().apply {
            userIds = listOf(USER_ID)
            validIdentity = true
        }
        val viewModel = AuthViewModel(dataSource, FakeAuthSessionDataSource())
        advanceUntilIdle()
        viewModel.showRegistration()
        viewModel.selectUser(USER_ID)
        viewModel.validateRegistrationCredentials(PHONE)
        advanceUntilIdle()
        dataSource.registeredIds += USER_ID

        viewModel.register(PHONE, "Test User", PASSWORD, PASSWORD)
        advanceUntilIdle()

        assertEquals(0, dataSource.registerCalls)
        assertEquals("This account has already been registered.", viewModel.uiState.value.errorMessage)
    }

    @Test
    fun repositoryException_exposesExplicitError() = runTest {
        val dataSource = FakeAuthDataSource().apply {
            userIds = listOf(USER_ID)
            registeredIds += USER_ID
            accounts[USER_ID] = account(USER_ID)
            loginFailure = IllegalStateException("database unavailable")
        }
        val viewModel = AuthViewModel(dataSource, FakeAuthSessionDataSource())
        advanceUntilIdle()
        viewModel.selectUser(USER_ID)
        advanceUntilIdle()

        viewModel.login(PASSWORD)
        advanceUntilIdle()

        assertEquals("Login failed: database unavailable", viewModel.uiState.value.errorMessage)
        assertFalse(viewModel.uiState.value.isSubmitting)
    }

    @Test
    fun repeatedLoginWhileRequestRunning_isIgnored() = runTest {
        val gate = CompletableDeferred<Unit>()
        val dataSource = FakeAuthDataSource().apply {
            userIds = listOf(USER_ID)
            registeredIds += USER_ID
            accounts[USER_ID] = account(USER_ID)
            loginGate = gate
        }
        val viewModel = AuthViewModel(dataSource, FakeAuthSessionDataSource())
        advanceUntilIdle()
        viewModel.selectUser(USER_ID)
        advanceUntilIdle()

        viewModel.login(PASSWORD)
        runCurrent()
        viewModel.login(PASSWORD)
        assertEquals(1, dataSource.loginCalls)

        gate.complete(Unit)
        advanceUntilIdle()
        assertNotNull(viewModel.uiState.value.navigation)
    }

    @Test
    fun handledNavigation_isNotReplayed() = runTest {
        val dataSource = FakeAuthDataSource().apply {
            userIds = listOf(USER_ID)
            registeredIds += USER_ID
            accounts[USER_ID] = account(USER_ID)
        }
        val viewModel = AuthViewModel(dataSource, FakeAuthSessionDataSource())
        advanceUntilIdle()
        viewModel.resolveLoggedInSession(USER_ID, AppRoute.INSIGHTS)
        advanceUntilIdle()
        val event = requireNotNull(viewModel.uiState.value.navigation)

        viewModel.navigationHandled(event.eventId)

        assertNull(viewModel.uiState.value.navigation)
    }

    private class FakeAuthSessionDataSource : AuthSessionDataSource {
        var savedUserId: Int? = null

        override suspend fun saveLoginSession(userId: Int) {
            savedUserId = userId
        }
    }

    private class FakeAuthDataSource : AuthDataSource {
        var userIds: List<Int> = emptyList()
        val registeredIds = mutableSetOf<Int>()
        val accounts = mutableMapOf<Int, UserAccount>()
        var validIdentity = false
        var completedQuestionnaire = false
        var loginFailure: Throwable? = null
        var loginGate: CompletableDeferred<Unit>? = null
        var loginCalls = 0
        var registerCalls = 0

        override suspend fun initializeDatabase() = SeedInitializationResult.ALREADY_INITIALIZED

        override fun observeAvailableUserIds(): Flow<List<Int>> = flowOf(userIds)

        override suspend fun isUserRegistered(userId: Int): Boolean = userId in registeredIds

        override suspend fun getUserAccount(userId: Int): UserAccount? = accounts[userId]

        override suspend fun validateLogin(userId: Int, password: String): UserAccount? {
            loginCalls++
            loginFailure?.let { throw it }
            loginGate?.await()
            return accounts[userId]?.takeIf { TEST_PASSWORD_HASHER.verify(password, it) }
        }

        override suspend fun isRegistrationIdentityValid(
            userId: Int,
            phoneNumber: String
        ): Boolean = validIdentity

        override suspend fun registerUser(
            userId: Int,
            phoneNumber: String,
            name: String,
            password: String
        ): Boolean {
            registerCalls++
            accounts[userId] = account(userId, phoneNumber, name, password)
            registeredIds += userId
            return true
        }

        override suspend fun updateLastLoginTime(userId: Int) = Unit

        override suspend fun migrateLegacyQuestionnaire(
            userId: Int
        ) = LegacyQuestionnaireMigrationResult.ALREADY_MIGRATED

        override suspend fun hasCompletedQuestionnaire(userId: Int) = completedQuestionnaire
    }

    private companion object {
        const val USER_ID = 501
        const val PHONE = "0412345678"
        const val PASSWORD = "Strong1!"

        val TEST_PASSWORD_HASHER = PasswordHasher(defaultIterations = 1_000)

        fun account(
            userId: Int,
            phoneNumber: String = PHONE,
            name: String = "Test User",
            password: String = PASSWORD
        ): UserAccount {
            val credential = TEST_PASSWORD_HASHER.hash(password)
            return UserAccount(
                userId = userId,
                phoneNumber = phoneNumber,
                name = name,
                passwordHash = credential.hash,
                passwordSalt = credential.salt,
                passwordAlgorithm = credential.algorithm,
                passwordAlgorithmVersion = credential.algorithmVersion,
                passwordIterations = credential.iterations,
                passwordKeyLengthBits = credential.keyLengthBits
            )
        }
    }
}
