package com.example.nutritrack

import android.graphics.Bitmap
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.nutritrack.data.AgentAuditUiModel
import com.example.nutritrack.data.AgentDraftPlanUiModel
import com.example.nutritrack.data.AgentRecommendationUiModel
import com.example.nutritrack.data.AppRoute
import com.example.nutritrack.data.AuthUiState
import com.example.nutritrack.data.CategoryScore
import com.example.nutritrack.data.DailyIntakeSummary
import com.example.nutritrack.data.DailyIntakeUiState
import com.example.nutritrack.data.DashboardUiState
import com.example.nutritrack.data.FoodIntake
import com.example.nutritrack.data.FruitNutritionUiState
import com.example.nutritrack.data.NutriCoachAgentUiState
import com.example.nutritrack.data.NutriCoachUiState
import com.example.nutritrack.data.Patient
import com.example.nutritrack.ui.theme.NutritrackTheme
import java.io.File
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Offline visual fixtures: no database, ViewModel, model Gateway, or network callbacks. */
@RunWith(AndroidJUnit4::class)
class VisualRefreshComposeTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun welcome_keepsLoginAndHealthBoundaryVisible() {
        render {
            WelcomeScreen(
                authUiState = authState(),
                onUserIdChanged = {}, onLogin = {}, onShowRegistration = {}, onShowLogin = {},
                onValidateRegistrationCredentials = {}, onRegister = { _, _, _, _ -> },
                onRetry = {}, onClearError = {}
            )
        }
        composeRule.onNodeWithTag("welcome_login").assertExists()
        composeRule.onNodeWithText("not intended as medical advice", substring = true).assertExists()
        capture("welcome")
        composeRule.onNodeWithTag("welcome_login").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun home_showsActualSyntheticMetricsAndNavigatesToIntake() {
        render {
            HomeScreen(
                dashboardUiState = dashboardState(),
                dailyIntakeUiState = intakeState(),
                onSelectRoute = {}, onSelectDate = {}, onAddDailyIntake = {},
                onEditDailyIntake = {}, onSaveDailyIntake = {}, onDismissDailyIntakeEditor = {},
                onClearDailyIntakeFeedback = {}, onRetryDashboard = {}, onLogout = {},
                onEditQuestionnaire = {}
            )
        }
        composeRule.onNodeWithTag("bottom_nav_home").assertExists()
        composeRule.onNodeWithTag("bottom_nav_insights").assertExists()
        composeRule.onNodeWithTag("bottom_nav_nutri_coach").assertExists()
        composeRule.onNodeWithTag("home_daily_intake").assertIsEnabled()
        capture("home")
        composeRule.onNodeWithTag("home_daily_intake").performScrollTo().performClick()
        composeRule.onNodeWithTag("daily_intake_add").assertIsDisplayed()
        composeRule.onNodeWithText("Synthetic balanced lunch").assertExists()
        capture("daily-intake")
    }

    @Test
    fun insights_showsFoodGroupScoresAndRecordedHistory() {
        render { InsightsScreen(uiState = dashboardState(), onRetry = {}, onNavigateToNutriCoach = {}) }
        composeRule.onNodeWithText("Total Food Quality Score").assertExists()
        composeRule.onNodeWithText("Fruits").assertExists()
        capture("insights")
        composeRule.onNodeWithText("Daily intake history").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(TEST_DATE.toString()).assertExists()
        capture("insights-history")
    }

    @Test
    fun coachDraft_showsRecommendationsSafetyAndExplicitActions() {
        var confirmations = 0
        render { CoachPreview(onConfirm = { confirmations++ }) }
        assertDraftContract()
        capture("coach-draft")
        composeRule.onNodeWithTag("agent_disclaimer").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("agent_audit_1").assertExists()
        composeRule.onNodeWithTag("agent_audit_2").assertExists()
        capture("coach-evidence")
        assertEquals("Rendering must never adopt the draft", 0, confirmations)
        composeRule.onNodeWithTag("agent_confirm_plan").performScrollTo().performClick()
        assertEquals("Only explicit action dispatches adoption", 1, confirmations)
    }

    @Test
    fun coachDraft_darkThemeRetainsAllSafetyAndActionContent() {
        render(dark = true) { CoachPreview() }
        assertDraftContract()
        capture("coach-draft-dark")
        composeRule.onNodeWithTag("agent_disclaimer").performScrollTo().assertIsDisplayed()
        capture("coach-evidence-dark")
    }

    @Test
    fun login_largerFontKeepsFormAndActionsReachable() {
        render(fontScale = 1.3f) {
            LoginScreen(
                uiState = authState(),
                onUserIdChanged = {}, onLogin = {}, onShowRegistration = {}, onShowLogin = {},
                onValidateRegistrationCredentials = {}, onRegister = { _, _, _, _ -> },
                onRetry = {}, onClearError = {}, onCancel = {}
            )
        }
        composeRule.onNodeWithTag("auth_user_id").assertIsDisplayed()
        composeRule.onNodeWithTag("auth_password").assertExists()
        capture("login-large-font")
        composeRule.onNodeWithTag("auth_login_submit").performScrollTo().assertIsDisplayed().assertIsEnabled()
        capture("login-large-font-actions")
    }

    @Test
    fun publicBuild_disablesAiButKeepsFruitLookupAvailable() {
        render {
            NutriCoachScreen(
                coachUiState = NutriCoachUiState(planningNeed = "Build balanced habits", isOptimalFruitScore = true),
                fruitNutritionUiState = FruitNutritionUiState(query = "apple"),
                onFruitQueryChanged = {}, onSearchFruit = {}, onRetryFruit = {},
                onPlanningNeedChanged = {}, onGeneratePlan = { error("AI must stay disabled") },
                onCancelPlan = {}, onRetryPlan = { error("AI must stay disabled") },
                onGenerateMotivationalMessage = { error("AI must stay disabled") }, aiAvailable = false
            )
        }
        composeRule.onNodeWithTag("coach_ai_unavailable").assertIsDisplayed()
        capture("coach-offline")
        composeRule.onNodeWithTag("agent_generate_plan").assertIsNotEnabled()
        composeRule.onNodeWithTag("fruit_search_submit").performScrollTo().assertIsEnabled()
        composeRule.onNodeWithTag("coach_generate_tip").performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun registration_sampleShortcutStillUsesCredentialValidation() {
        var validatedPhone = ""
        render {
            RegisterScreen(
                uiState = AuthUiState(selectedUserId = 1, unregisteredUserIds = listOf(1), isLoadingUsers = false),
                onUserIdChanged = {}, onValidateCredentials = { validatedPhone = it },
                onRegister = { _, _, _, _ -> error("The shortcut must never register automatically") },
                onNavigateToLogin = {}, onClearError = {}
            )
        }
        composeRule.onNodeWithTag("register_sample_phone").performScrollTo().performClick()
        assertEquals("2025550101", validatedPhone)
        capture("registration")
    }

    @Test
    fun settings_largeFontKeepsAccountStatusAndLogoutReachable() {
        render(fontScale = 1.3f) {
            SettingsScreen(
                uiState = com.example.nutritrack.data.SettingsUiState(
                    account = com.example.nutritrack.data.SettingsAccount(1, "Sample explorer", "2025550101"), isLoading = false),
                aiAvailable = false, onRetry = {}, onLogout = {}
            )
        }
        composeRule.onNodeWithText("Sample explorer").assertIsDisplayed()
        capture("settings")
        composeRule.onNodeWithText("Not configured in this build").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("settings_logout").performScrollTo().performClick()
        composeRule.onNodeWithText("Log out?").assertIsDisplayed()
    }

    private fun render(dark: Boolean = false, fontScale: Float = 1f, content: @Composable () -> Unit) {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                NutritrackTheme(darkTheme = dark, dynamicColor = false) {
                    Surface(modifier = Modifier.fillMaxSize()) { content() }
                }
            }
        }
        composeRule.waitForIdle()
    }

    @Composable
    private fun CoachPreview(onConfirm: () -> Unit = {}) {
        NutriCoachScreen(
            coachUiState = NutriCoachUiState(
                isOptimalFruitScore = false,
                planningNeed = "Use my synthetic intake history to build balanced habits.",
                agentState = NutriCoachAgentUiState.DraftReady(
                    draft = AgentDraftPlanUiModel(
                        planId = "visual-plan", runId = "visual-run",
                        summary = "A calmer, more balanced week",
                        validFrom = "2026-10-13", validTo = "2026-10-19",
                        recommendations = listOf(
                            AgentRecommendationUiModel(
                                title = "Make space for vegetables",
                                action = "Add a serving of vegetables to your main meal.",
                                target = "2 servings per day"
                            )
                        ),
                        cautions = listOf("Adjust gradually based on comfort."),
                        disclaimer = "General nutrition guidance only; not medical advice."
                    ),
                    audit = listOf(
                        AgentAuditUiModel(1, "Nutrition profile", "SUCCEEDED", "09:00"),
                        AgentAuditUiModel(2, "Intake history", "SUCCEEDED", "09:00")
                    )
                )
            ),
            fruitNutritionUiState = FruitNutritionUiState(),
            onFruitQueryChanged = {}, onSearchFruit = {}, onRetryFruit = {},
            onPlanningNeedChanged = {}, onGeneratePlan = {}, onCancelPlan = {}, onRetryPlan = {},
            onConfirmPlan = onConfirm, onAbandonPlan = {}, onGenerateMotivationalMessage = {}, aiAvailable = false
        )
    }

    private fun assertDraftContract() {
        listOf("agent_draft", "agent_summary", "agent_recommendation_0", "agent_cautions", "agent_disclaimer", "agent_audit")
            .forEach { composeRule.onNodeWithTag(it).assertExists() }
        composeRule.onNodeWithText("DRAFT · NOT ACTIVE").assertExists()
        composeRule.onNodeWithTag("agent_confirm_plan").assertIsEnabled()
        composeRule.onNodeWithTag("agent_abandon_plan").assertIsEnabled()
    }

    private fun capture(name: String) {
        composeRule.waitForIdle()
        val bitmap = composeRule.onRoot().captureToImage().asAndroidBitmap()
        val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "ui-refresh-preview")
        assertTrue(directory.isDirectory || directory.mkdirs())
        val target = File(directory, "$name.png")
        target.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        assertTrue("Visual capture must contain rendered pixels", target.length() > 0L)
    }

    private fun authState() = AuthUiState(
        availableUserIds = listOf(USER_ID), selectedUserId = USER_ID,
        selectedUserIsRegistered = true, isLoadingUsers = false
    )

    private fun intakeState() = DailyIntakeUiState(
        selectedDate = TEST_DATE,
        records = listOf(FoodIntake(id = 1, patientUserId = USER_ID, submissionDate = 0L,
            foodName = "Synthetic balanced lunch", vegetablesServings = 2.0, fruitServings = 1.0, waterMl = 550.0)),
        summaries = listOf(summary()), isLoading = false, isHistoryLoading = false
    )

    private fun summary() = DailyIntakeSummary(
        localDate = TEST_DATE, recordCount = 1, vegetablesServings = 2.0, fruitServings = 1.0,
        grainsServings = 1.0, meatServings = 1.0, dairyServings = 0.0,
        discretionaryServings = 0.0, waterMl = 550.0, sugarG = 3.0
    )

    private fun dashboardState() = DashboardUiState(
        userId = USER_ID, selectedRoute = AppRoute.HOME, patient = syntheticPatient(),
        foodQualityScore = 74f,
        categoryScores = listOf(CategoryScore("Vegetables", 7.5f), CategoryScore("Fruits", 8f), CategoryScore("Water", 3.5f, 5f)),
        dailyIntakeSummaries = listOf(summary()), isLoading = false, isHistoryLoading = false
    )

    private fun syntheticPatient() = Patient(
        userId = USER_ID, phoneNumber = "0000000000", name = "Synthetic visual fixture", sex = "Female",
        hEIFAtotalscoreMale = 74.0, hEIFAtotalscoreFemale = 74.0,
        discretionaryHEIFAscoreMale = 7.0, discretionaryHEIFAscoreFemale = 7.0, discretionaryservesize = 0.0,
        vegetablesHEIFAscoreMale = 7.5, vegetablesHEIFAscoreFemale = 7.5,
        vegetableswithlegumesallocatedservesize = 2.0, legumesallocatedVegetables = 0.0,
        vegetablesvariationsscore = 2.0, vegetablesCruciferous = 0.0, vegetablesTuberandbulb = 0.0,
        vegetablesOther = 0.0, legumes = 0.0, vegetablesGreen = 1.0, vegetablesRedandorange = 1.0,
        fruitHEIFAscoreMale = 8.0, fruitHEIFAscoreFemale = 8.0, fruitservesize = 1.0, fruitvariationsscore = 1.0,
        fruitPome = 1.0, fruitTropicalandsubtropical = 0.0, fruitBerry = 0.0, fruitStone = 0.0,
        fruitCitrus = 0.0, fruitOther = 0.0, grainsandcerealsHEIFAscoreMale = 3.0,
        grainsandcerealsHEIFAscoreFemale = 3.0, grainsandcerealsservesize = 1.0,
        grainsandcerealsNonwholegrains = 0.0, wholegrainsHEIFAscoreMale = 3.0,
        wholegrainsHEIFAscoreFemale = 3.0, wholegrainsservesize = 1.0,
        meatandalternativesHEIFAscoreMale = 7.0, meatandalternativesHEIFAscoreFemale = 7.0,
        meatandalternativeswithlegumesallocatedservesize = 1.0, legumesallocatedMeatandalternatives = 0.0,
        dairyandalternativesHEIFAscoreMale = 7.0, dairyandalternativesHEIFAscoreFemale = 7.0,
        dairyandalternativesservesize = 0.0, sodiumHEIFAscoreMale = 7.0, sodiumHEIFAscoreFemale = 7.0,
        sodiummgmilligrams = 500.0, alcoholHEIFAscoreMale = 5.0, alcoholHEIFAscoreFemale = 5.0,
        alcoholstandarddrinks = 0.0, waterHEIFAscoreMale = 3.5, waterHEIFAscoreFemale = 3.5,
        water = 1.0, waterTotalmL = 550.0, beverageTotalmL = 550.0,
        sugarHEIFAscoreMale = 7.0, sugarHEIFAscoreFemale = 7.0, sugar = 3.0,
        saturatedFatHEIFAscoreMale = 3.0, saturatedFatHEIFAscoreFemale = 3.0, saturatedFat = 1.0,
        unsaturatedFatHEIFAscoreMale = 4.0, unsaturatedFatHEIFAscoreFemale = 4.0, unsaturatedFatservesize = 1.0
    )

    private companion object {
        const val USER_ID = 799
        val TEST_DATE: LocalDate = LocalDate.of(2026, 10, 13)
    }
}
