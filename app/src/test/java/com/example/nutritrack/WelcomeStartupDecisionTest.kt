package com.example.nutritrack

import com.example.nutritrack.data.SessionSnapshot
import com.example.nutritrack.data.SessionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WelcomeStartupDecisionTest {
    @Test
    fun loadingDoesNotNavigateOrShowLoggedOutContent() {
        val action = decideWelcomeStartup(SessionSnapshot(SessionState.Loading))

        assertEquals(WelcomeStartupAction.Wait, action)
    }

    @Test
    fun loggedOutShowsWelcomeAndLoggedInResolvesUser() {
        assertEquals(
            WelcomeStartupAction.ShowWelcome,
            decideWelcomeStartup(SessionSnapshot(SessionState.LoggedOut))
        )
        val loggedInAction = decideWelcomeStartup(
            SessionSnapshot(SessionState.LoggedIn(703))
        )
        assertTrue(loggedInAction is WelcomeStartupAction.ResolveLoggedIn)
        assertEquals(703, (loggedInAction as WelcomeStartupAction.ResolveLoggedIn).userId)
    }

    @Test
    fun configurationReadErrorHasExplicitNonNavigatingAction() {
        val action = decideWelcomeStartup(
            SessionSnapshot(SessionState.ReadError("Unable to read configuration"))
        )

        assertTrue(action is WelcomeStartupAction.ShowConfigurationError)
    }
}
