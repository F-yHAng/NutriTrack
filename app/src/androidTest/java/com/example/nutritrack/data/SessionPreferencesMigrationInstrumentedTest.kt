package com.example.nutritrack.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.SharedPreferencesMigration
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SessionPreferencesMigrationInstrumentedTest {
    private lateinit var context: Context
    private val handles = mutableListOf<StoreHandle>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        legacyPreferences().edit().clear().commit()
    }

    @After
    fun tearDown() {
        runBlocking {
            handles.mapNotNull { handle ->
                handle.scope.cancel()
                handle.scope.coroutineContext[Job]
            }.joinAll()
        }
        handles.forEach { handle ->
            context.preferencesDataStoreFile(handle.name).delete()
        }
        legacyPreferences().edit().clear().commit()
    }

    @Test
    fun validLegacyValues_migrateOnlyAllowedKeysAndDeriveLoggedInState() = runBlocking {
        val legacy = legacyPreferences()
        assertTrue(
            legacy.edit()
                .putBoolean("is_logged_in", false)
                .putInt(CURRENT_USER_ID_KEY_NAME, 801)
                .putString(LAST_PAGE_KEY_NAME, "Insights")
                .putString("current_phone_number", "0400000801")
                .putString("current_user_name", "Legacy User")
                .putString("selected_persona", "Mindful Eater")
                .commit()
        )
        val handle = newStore()

        val snapshot = handle.manager.readyState()

        assertEquals(SessionState.LoggedIn(801), snapshot.sessionState)
        assertEquals(AppRoute.INSIGHTS, snapshot.lastPage)
        assertEquals(
            setOf(CURRENT_USER_ID_KEY_NAME, LAST_PAGE_KEY_NAME),
            handle.dataStore.data.first().asMap().keys.map { it.name }.toSet()
        )
        assertFalse(legacy.contains(CURRENT_USER_ID_KEY_NAME))
        assertFalse(legacy.contains(LAST_PAGE_KEY_NAME))
        assertTrue(legacy.contains("is_logged_in"))
        assertTrue(legacy.contains("current_phone_number"))
        assertTrue(legacy.contains("current_user_name"))
        assertTrue(legacy.contains("selected_persona"))
    }

    @Test
    fun invalidLegacyUserAndUnknownRoute_useSafeDefaults() = runBlocking {
        assertTrue(
            legacyPreferences().edit()
                .putBoolean("is_logged_in", true)
                .putString(CURRENT_USER_ID_KEY_NAME, "not-an-integer")
                .putString(LAST_PAGE_KEY_NAME, "deleted-route")
                .commit()
        )

        val snapshot = newStore().manager.readyState()

        assertEquals(SessionState.LoggedOut, snapshot.sessionState)
        assertEquals(AppRoute.HOME, snapshot.lastPage)
    }

    @Test
    fun missingUserIsLoggedOutWhileKnownLastPageRestores() = runBlocking {
        assertTrue(legacyPreferences().edit().putString(LAST_PAGE_KEY_NAME, "NutriCoach").commit())

        val snapshot = newStore().manager.readyState()

        assertEquals(SessionState.LoggedOut, snapshot.sessionState)
        assertEquals(AppRoute.NUTRI_COACH, snapshot.lastPage)
    }

    @Test
    fun freshInstallUsesLoggedOutAndHomeDefaults() = runBlocking {
        val handle = newStore()

        val snapshot = handle.manager.readyState()

        assertEquals(SessionState.LoggedOut, snapshot.sessionState)
        assertEquals(AppRoute.HOME, snapshot.lastPage)
        assertTrue(handle.dataStore.data.first().asMap().isEmpty())
    }

    @Test
    fun logoutRemovesUserAndLastPageAndPublishesLoggedOut() = runBlocking {
        assertTrue(
            legacyPreferences().edit()
                .putInt(CURRENT_USER_ID_KEY_NAME, 802)
                .putString(LAST_PAGE_KEY_NAME, "Settings")
                .commit()
        )
        val handle = newStore()
        assertEquals(SessionState.LoggedIn(802), handle.manager.readyState().sessionState)

        handle.manager.clearSession()
        val loggedOut = handle.manager.readyState()

        assertEquals(SessionState.LoggedOut, loggedOut.sessionState)
        assertEquals(AppRoute.HOME, loggedOut.lastPage)
        assertTrue(handle.dataStore.data.first().asMap().isEmpty())
    }

    @Test
    fun repeatedInitializationDoesNotOverwriteNewDataStoreValues() = runBlocking {
        val name = uniqueStoreName()
        assertTrue(
            legacyPreferences().edit()
                .putInt(CURRENT_USER_ID_KEY_NAME, 803)
                .putString(LAST_PAGE_KEY_NAME, "Insights")
                .commit()
        )
        val first = newStore(name)
        assertEquals(SessionState.LoggedIn(803), first.manager.readyState().sessionState)
        first.manager.saveLoginSession(804)
        first.manager.saveLastPage(AppRoute.SETTINGS)
        stop(first)

        assertTrue(
            legacyPreferences().edit()
                .putInt(CURRENT_USER_ID_KEY_NAME, 999)
                .putString(LAST_PAGE_KEY_NAME, "Home")
                .commit()
        )
        val second = newStore(name)
        val snapshot = second.manager.readyState()

        assertEquals(SessionState.LoggedIn(804), snapshot.sessionState)
        assertEquals(AppRoute.SETTINGS, snapshot.lastPage)
    }

    private fun newStore(name: String = uniqueStoreName()): StoreHandle {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val dataStore = PreferenceDataStoreFactory.create(
            migrations = listOf(
                SharedPreferencesMigration(
                    context = context,
                    sharedPreferencesName = LEGACY_SESSION_PREFERENCES,
                    keysToMigrate = setOf(CURRENT_USER_ID_KEY_NAME, LAST_PAGE_KEY_NAME)
                )
            ),
            scope = scope,
            produceFile = { context.preferencesDataStoreFile(name) }
        )
        return StoreHandle(
            name = name,
            dataStore = dataStore,
            manager = SessionManager(dataStore),
            scope = scope
        ).also(handles::add)
    }

    private suspend fun stop(handle: StoreHandle) {
        handle.scope.cancel()
        handle.scope.coroutineContext[Job]?.join()
    }

    private suspend fun SessionManager.readyState(): SessionSnapshot = state
        .dropWhile { it.sessionState == SessionState.Loading }
        .first()

    private fun legacyPreferences() = context.getSharedPreferences(
        LEGACY_SESSION_PREFERENCES,
        Context.MODE_PRIVATE
    )

    private fun uniqueStoreName(): String = "p0_4_${UUID.randomUUID()}"

    private data class StoreHandle(
        val name: String,
        val dataStore: DataStore<Preferences>,
        val manager: SessionManager,
        val scope: CoroutineScope
    )
}
