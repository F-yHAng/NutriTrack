package com.example.nutritrack.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.SharedPreferencesMigration
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

internal const val LEGACY_SESSION_PREFERENCES = "nutritrack_session"
internal const val CURRENT_USER_ID_KEY_NAME = "current_user_id"
internal const val LAST_PAGE_KEY_NAME = "last_page"
internal const val APP_PREFERENCES_DATASTORE_NAME = "nutritrack_preferences"

private val Context.applicationPreferencesDataStore: DataStore<Preferences> by preferencesDataStore(
    name = APP_PREFERENCES_DATASTORE_NAME,
    produceMigrations = { context ->
        listOf(
            SharedPreferencesMigration(
                context = context,
                sharedPreferencesName = LEGACY_SESSION_PREFERENCES,
                keysToMigrate = setOf(CURRENT_USER_ID_KEY_NAME, LAST_PAGE_KEY_NAME)
            )
        )
    }
)

enum class AppRoute(val storedValue: String) {
    HOME("home"),
    DAILY_INTAKE("daily_intake"),
    INSIGHTS("insights"),
    NUTRI_COACH("nutri_coach"),
    SETTINGS("settings");

    companion object {
        fun fromStoredValue(value: String?): AppRoute {
            val normalized = value?.trim().orEmpty()
            return entries.firstOrNull { it.storedValue == normalized }
                ?: when (normalized) {
                    "Home" -> HOME
                    "DailyIntake" -> DAILY_INTAKE
                    "Insights" -> INSIGHTS
                    "NutriCoach" -> NUTRI_COACH
                    "Settings" -> SETTINGS
                    else -> HOME
                }
        }
    }
}

sealed interface SessionState {
    data object Loading : SessionState
    data object LoggedOut : SessionState
    data class LoggedIn(val userId: Int) : SessionState
    data class ReadError(val message: String) : SessionState
}

data class SessionSnapshot(
    val sessionState: SessionState,
    val lastPage: AppRoute = AppRoute.HOME
)

class SessionManager internal constructor(
    private val dataStore: DataStore<Preferences>
) : AuthSessionDataSource, DashboardSessionDataSource {
    val state: Flow<SessionSnapshot> = dataStore.data
        .map(::toSessionSnapshot)
        .catch { exception ->
            if (exception is IOException) {
                emit(
                    SessionSnapshot(
                        sessionState = SessionState.ReadError(
                            "Unable to read saved app configuration"
                        ),
                        lastPage = AppRoute.HOME
                    )
                )
            } else {
                throw exception
            }
        }
        .onStart { emit(SessionSnapshot(SessionState.Loading)) }
        .distinctUntilChanged()

    override suspend fun saveLoginSession(userId: Int) {
        require(userId > 0) { "A valid user ID is required" }
        dataStore.edit { preferences ->
            preferences[CURRENT_USER_ID_KEY] = userId
        }
    }

    override suspend fun clearSession() {
        dataStore.edit { preferences ->
            preferences.remove(CURRENT_USER_ID_KEY)
            preferences.remove(LAST_PAGE_KEY)
        }
    }

    override suspend fun saveLastPage(route: AppRoute) {
        dataStore.edit { preferences ->
            preferences[LAST_PAGE_KEY] = route.storedValue
        }
    }

    private fun toSessionSnapshot(preferences: Preferences): SessionSnapshot {
        val valuesByName = preferences.asMap().entries.associate { (key, value) ->
            key.name to value
        }
        val currentUserId = (valuesByName[CURRENT_USER_ID_KEY_NAME] as? Int)
            ?.takeIf { it > 0 }
        val lastPage = AppRoute.fromStoredValue(valuesByName[LAST_PAGE_KEY_NAME] as? String)
        return SessionSnapshot(
            sessionState = currentUserId?.let(SessionState::LoggedIn)
                ?: SessionState.LoggedOut,
            lastPage = lastPage
        )
    }

    companion object {
        private val CURRENT_USER_ID_KEY = intPreferencesKey(CURRENT_USER_ID_KEY_NAME)
        private val LAST_PAGE_KEY = stringPreferencesKey(LAST_PAGE_KEY_NAME)

        fun create(context: Context): SessionManager = SessionManager(
            context.applicationContext.applicationPreferencesDataStore
        )
    }
}
