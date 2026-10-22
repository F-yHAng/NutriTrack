package com.example.nutritrack.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    @get:Rule val mainDispatcherRule = MainDispatcherRule()

    @Test fun accountLoad_isScopedToTheSignedInUser() = runTest {
        var requestedId = 0
        val vm = SettingsViewModel(object : SettingsDataSource {
            override suspend fun getSettingsAccount(userId: Int): SettingsAccount {
                requestedId = userId
                return SettingsAccount(userId, "Sample user", "2025550101")
            }
        }, 1)
        advanceUntilIdle()
        assertEquals(1, requestedId)
        assertEquals("Sample user", vm.uiState.value.account?.name)
        assertFalse(vm.uiState.value.isLoading)
    }

    @Test fun failure_isRedactedAndCanBeRetried() = runTest {
        var fail = true
        val vm = SettingsViewModel(object : SettingsDataSource {
            override suspend fun getSettingsAccount(userId: Int): SettingsAccount {
                if (fail) error("private fixture diagnostic")
                return SettingsAccount(userId, "Sample", "2025550101")
            }
        }, 1)
        advanceUntilIdle()
        assertFalse(vm.uiState.value.errorMessage.orEmpty().contains("private fixture"))
        assertNotNull(vm.uiState.value.errorMessage)
        fail = false
        vm.refresh()
        advanceUntilIdle()
        assertNull(vm.uiState.value.errorMessage)
        assertNotNull(vm.uiState.value.account)
    }

    @Test fun unexpectedAccountOwner_isNeverDisplayed() = runTest {
        val vm = SettingsViewModel(object : SettingsDataSource {
            override suspend fun getSettingsAccount(userId: Int) = SettingsAccount(2, "Other", "2025550102")
        }, 1)
        advanceUntilIdle()
        assertNull(vm.uiState.value.account)
        assertNotNull(vm.uiState.value.errorMessage)
    }

    @Test fun invalidSession_doesNotReadAnAccount() = runTest {
        var reads = 0
        val vm = SettingsViewModel(object : SettingsDataSource {
            override suspend fun getSettingsAccount(userId: Int): SettingsAccount? { reads++; return null }
        }, 0)
        advanceUntilIdle()
        assertEquals(0, reads)
        assertNotNull(vm.uiState.value.errorMessage)
    }

    @Test fun repeatedRetry_doesNotStartConcurrentReads() = runTest {
        val response = CompletableDeferred<SettingsAccount?>()
        var reads = 0
        val vm = SettingsViewModel(object : SettingsDataSource {
            override suspend fun getSettingsAccount(userId: Int): SettingsAccount? { reads++; return response.await() }
        }, 1)
        runCurrent()
        vm.refresh()
        vm.refresh()
        assertEquals(1, reads)
        response.complete(SettingsAccount(1, "Sample", "2025550101"))
        advanceUntilIdle()
        assertNotNull(vm.uiState.value.account)
    }

    @Test fun cancellation_doesNotBecomeAnAccountError() = runTest {
        val vm = SettingsViewModel(object : SettingsDataSource {
            override suspend fun getSettingsAccount(userId: Int): SettingsAccount? = throw CancellationException()
        }, 1)
        advanceUntilIdle()
        assertNull(vm.uiState.value.errorMessage)
    }
}
