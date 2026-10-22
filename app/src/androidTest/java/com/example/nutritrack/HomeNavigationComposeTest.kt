package com.example.nutritrack

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.testing.TestNavHostController
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.nutritrack.data.AppRoute
import com.example.nutritrack.data.SessionSnapshot
import com.example.nutritrack.data.SessionState
import com.example.nutritrack.ui.theme.NutritrackTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HomeNavigationComposeTest {
    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var navController: TestNavHostController

    @Test
    fun defaultAndUnknownRoutes_enterHome() {
        assertEquals(AppRoute.HOME, AppRoute.fromStoredValue("removed-route"))

        setNavigation(AppRoute.fromStoredValue("removed-route"))

        waitForRoute(AppRoute.HOME)
        composeRule.onNodeWithTag("destination_home").assertExists()
    }

    @Test
    fun knownLastPage_isRestoredAndBackReturnsHome() {
        setNavigation(AppRoute.INSIGHTS)

        waitForRoute(AppRoute.INSIGHTS)
        composeRule.onNodeWithTag("destination_insights").assertExists()

        composeRule.runOnIdle { assertTrue(navController.popBackStack()) }
        waitForRoute(AppRoute.HOME)
    }

    @Test
    fun bottomBar_switchesHomeInsightsNutriCoachAndSettings() {
        setNavigation(AppRoute.HOME)

        listOf(AppRoute.INSIGHTS, AppRoute.NUTRI_COACH, AppRoute.SETTINGS, AppRoute.HOME)
            .forEach { route ->
                composeRule.onNodeWithTag("bottom_nav_${route.storedValue}").performClick()
                waitForRoute(route)
                composeRule.onNodeWithTag("destination_${route.storedValue}").assertExists()
            }
    }

    @Test
    fun repeatedDestinationClick_doesNotAddAnotherBackStackEntry() {
        setNavigation(AppRoute.HOME)
        composeRule.onNodeWithTag("bottom_nav_insights").performClick()
        waitForRoute(AppRoute.INSIGHTS)

        composeRule.onNodeWithTag("bottom_nav_insights").performClick()
        waitForRoute(AppRoute.INSIGHTS)
        composeRule.runOnIdle { assertTrue(navController.popBackStack()) }

        waitForRoute(AppRoute.HOME)
    }

    @Test
    fun topLevelNavigation_restoresDestinationUiState() {
        setNavigation(AppRoute.HOME)
        composeRule.onNodeWithTag("state_increment").performClick()
        composeRule.onNodeWithTag("state_value_1").assertExists()

        composeRule.onNodeWithTag("bottom_nav_insights").performClick()
        waitForRoute(AppRoute.INSIGHTS)
        composeRule.onNodeWithTag("bottom_nav_home").performClick()
        waitForRoute(AppRoute.HOME)

        composeRule.onNodeWithTag("state_value_1").assertExists()
    }

    @Test
    fun loggedOutSession_removesProtectedNavigationAndCannotReturnToIt() {
        var snapshot by mutableStateOf(
            SessionSnapshot(SessionState.LoggedIn(userId = 77), AppRoute.HOME)
        )
        var redirectCount = 0
        composeRule.setContent {
            NutritrackTheme {
                HomeSessionGate(
                    snapshot = snapshot,
                    onSessionUnavailable = { redirectCount++ }
                ) {
                    TestNavigation(AppRoute.HOME)
                }
            }
        }
        waitForRoute(AppRoute.HOME)
        composeRule.onNodeWithTag("destination_home").assertExists()

        composeRule.runOnIdle {
            snapshot = SessionSnapshot(SessionState.LoggedOut, AppRoute.HOME)
        }
        composeRule.waitUntil { redirectCount == 1 }
        composeRule.onNodeWithTag("destination_home").assertDoesNotExist()
        composeRule.runOnIdle { navController.popBackStack() }
        composeRule.onNodeWithTag("destination_home").assertDoesNotExist()
    }

    private fun setNavigation(initialRoute: AppRoute) {
        composeRule.setContent {
            NutritrackTheme { TestNavigation(initialRoute) }
        }
    }

    @androidx.compose.runtime.Composable
    private fun TestNavigation(initialRoute: AppRoute) {
        val context = LocalContext.current
        navController = remember {
            TestNavHostController(context).apply {
                navigatorProvider.addNavigator(ComposeNavigator())
            }
        }
        HomeNavigationScaffold(
            navController = navController,
            initialRoute = initialRoute,
            onRouteChanged = {}
        ) { route, _, _, contentModifier ->
            var counter by rememberSaveable { mutableIntStateOf(0) }
            Box(modifier = contentModifier) {
                Column {
                    Text(route.storedValue)
                    Button(
                        onClick = { counter++ },
                        modifier = Modifier.testTag("state_increment")
                    ) {
                        Text("Increment")
                    }
                    Text(counter.toString(), Modifier.testTag("state_value_$counter"))
                }
            }
        }
    }

    private fun waitForRoute(route: AppRoute) {
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            ::navController.isInitialized &&
                navController.currentBackStackEntry?.destination?.route == route.storedValue
        }
    }
}
