package com.example.nutritrack.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionManagerTest {
    @Test
    fun validUserAndKnownLegacyRoute_areMappedWithoutLoginBoolean() = runTest {
        val manager = SessionManager(
            FakePreferencesDataStore(
                mutablePreferencesOf(
                    intPreferencesKey(CURRENT_USER_ID_KEY_NAME) to 701,
                    stringPreferencesKey(LAST_PAGE_KEY_NAME) to "Insights"
                )
            )
        )

        val snapshot = manager.readyState()

        assertEquals(SessionState.LoggedIn(701), snapshot.sessionState)
        assertEquals(AppRoute.INSIGHTS, snapshot.lastPage)
    }

    @Test
    fun missingOrInvalidUser_isLoggedOutAndUnknownRouteFallsBackHome() = runTest {
        val invalidManager = SessionManager(
            FakePreferencesDataStore(
                mutablePreferencesOf(
                    intPreferencesKey(CURRENT_USER_ID_KEY_NAME) to -4,
                    stringPreferencesKey(LAST_PAGE_KEY_NAME) to "removed-screen"
                )
            )
        )
        val freshManager = SessionManager(FakePreferencesDataStore(emptyPreferences()))

        assertEquals(SessionState.LoggedOut, invalidManager.readyState().sessionState)
        assertEquals(AppRoute.HOME, invalidManager.readyState().lastPage)
        assertEquals(AppRoute.HOME, AppRoute.fromStoredValue("   "))
        assertEquals(SessionState.LoggedOut, freshManager.readyState().sessionState)
        assertEquals(AppRoute.HOME, freshManager.readyState().lastPage)
    }

    @Test
    fun loginAndLogout_updateStateAndLogoutAlsoResetsLastPage() = runTest {
        val dataStore = FakePreferencesDataStore(emptyPreferences())
        val manager = SessionManager(dataStore)

        manager.saveLoginSession(702)
        manager.saveLastPage(AppRoute.SETTINGS)
        assertEquals(SessionState.LoggedIn(702), manager.readyState().sessionState)
        assertEquals(AppRoute.SETTINGS, manager.readyState().lastPage)

        manager.clearSession()

        assertEquals(SessionState.LoggedOut, manager.readyState().sessionState)
        assertEquals(AppRoute.HOME, manager.readyState().lastPage)
        assertTrue(dataStore.current.asMap().isEmpty())
    }

    @Test
    fun ioReadFailure_emitsExplicitSafeFallbackState() = runTest {
        val manager = SessionManager(
            FakePreferencesDataStore(readFailure = IOException("simulated read failure"))
        )

        val emissions = manager.state.toList()

        assertEquals(SessionState.Loading, emissions.first().sessionState)
        assertTrue(emissions.last().sessionState is SessionState.ReadError)
        assertEquals(AppRoute.HOME, emissions.last().lastPage)
    }

    @Test(expected = IllegalStateException::class)
    fun nonIoReadFailure_isNotSwallowed() = runTest {
        SessionManager(
            FakePreferencesDataStore(readFailure = IllegalStateException("programming error"))
        ).state.toList()
    }

    private suspend fun SessionManager.readyState(): SessionSnapshot = state
        .dropWhile { it.sessionState == SessionState.Loading }
        .first()

    private class FakePreferencesDataStore(
        initialPreferences: Preferences = emptyPreferences(),
        readFailure: Throwable? = null
    ) : DataStore<Preferences> {
        private val mutableData = MutableStateFlow(initialPreferences)
        val current: Preferences get() = mutableData.value

        override val data: Flow<Preferences> = readFailure?.let { failure ->
            flow { throw failure }
        } ?: mutableData

        override suspend fun updateData(
            transform: suspend (t: Preferences) -> Preferences
        ): Preferences {
            val updated = transform(mutableData.value)
            mutableData.value = updated
            return updated
        }
    }
}
