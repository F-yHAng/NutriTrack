package com.example.nutritrack

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.nutritrack.data.AppRoute
import com.example.nutritrack.data.DailyIntakeFormInput
import com.example.nutritrack.data.DailyIntakeUiState
import com.example.nutritrack.data.DailyIntakeViewModel
import com.example.nutritrack.data.DashboardUiState
import com.example.nutritrack.data.DashboardViewModel
import com.example.nutritrack.data.FruitNutritionUiState
import com.example.nutritrack.data.FruitNutritionViewModel
import com.example.nutritrack.data.NutriCoachUiState
import com.example.nutritrack.data.NutriCoachViewModel
import com.example.nutritrack.data.SettingsUiState
import com.example.nutritrack.data.SettingsViewModel
import com.example.nutritrack.data.SessionSnapshot
import com.example.nutritrack.data.SessionState
import com.example.nutritrack.ui.components.NutriCard
import com.example.nutritrack.ui.components.NutriEmptyState
import com.example.nutritrack.ui.components.NutriPageHeader
import com.example.nutritrack.ui.components.NutriPrimaryButton
import com.example.nutritrack.ui.components.NutriSectionTitle
import com.example.nutritrack.ui.theme.NutritrackTheme
import java.time.LocalDate

class HomeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val application = applicationContext as NutrackApplication
        val phoneNumber = intent.getStringExtra("PHONE_NUMBER") ?: ""
        val navTarget = intent.getStringExtra("NAV")

        setContent {
            NutritrackTheme {
                val snapshot by application.sessionManager.state.collectAsStateWithLifecycle(
                    initialValue = SessionSnapshot(SessionState.Loading)
                )
                HomeSessionContent(
                    snapshot = snapshot,
                    phoneNumber = phoneNumber,
                    requestedRoute = navTarget,
                    application = application
                )
            }
        }
    }
}

@Composable
private fun HomeSessionContent(
    snapshot: SessionSnapshot,
    phoneNumber: String,
    requestedRoute: String?,
    application: NutrackApplication
) {
    val context = LocalContext.current
    HomeSessionGate(
        snapshot = snapshot,
        onSessionUnavailable = {
            val intent = Intent(context, WelcomeActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            context.startActivity(intent)
        }
    ) { sessionState ->
            val dailyIntakeViewModel: DailyIntakeViewModel = viewModel(
                key = "daily-intake-${sessionState.userId}",
                factory = DailyIntakeViewModel.Factory(
                    application.repository,
                    sessionState.userId
                )
            )
            val initialRoute = requestedRoute
                ?.let(AppRoute::fromStoredValue)
                ?: snapshot.lastPage
            val dashboardViewModel: DashboardViewModel = viewModel(
                key = "dashboard-${sessionState.userId}",
                factory = DashboardViewModel.Factory(
                    dataSource = application.repository,
                    sessionDataSource = application.sessionManager,
                    userId = sessionState.userId,
                    initialRoute = initialRoute,
                    dailyIntakeState = dailyIntakeViewModel.uiState
                )
            )
            val fruitNutritionViewModel: FruitNutritionViewModel = viewModel(
                key = "fruit-nutrition-${sessionState.userId}",
                factory = FruitNutritionViewModel.Factory(application.fruitNutritionGateway)
            )
            val nutriCoachViewModel: NutriCoachViewModel = viewModel(
                key = "nutri-coach-${sessionState.userId}",
                factory = NutriCoachViewModel.Factory(
                    sessionState = application.sessionManager.state,
                    contentDataSource = application.repository,
                    aiGateway = application.aiGateway,
                    planRunner = application.nutritionPlanningAgent,
                    runHistory = application.agentRepository,
                    planActionDispatcher = application.nutritionPlanActionDispatcher,
                    planLifecycleScheduler = application.nutritionPlanLifecycleScheduler,
                    planParser = application.nutritionPlanParser,
                    clock = application.clock
                )
            )
            val settingsViewModel: SettingsViewModel = viewModel(
                key = "settings-${sessionState.userId}",
                factory = SettingsViewModel.Factory(application.repository, sessionState.userId)
            )
            val settingsUiState by settingsViewModel.uiState.collectAsStateWithLifecycle()
            val dailyIntakeUiState by dailyIntakeViewModel.uiState.collectAsStateWithLifecycle()
            val dashboardUiState by dashboardViewModel.uiState.collectAsStateWithLifecycle()
            val fruitNutritionUiState by fruitNutritionViewModel.uiState.collectAsStateWithLifecycle()
            val nutriCoachUiState by nutriCoachViewModel.uiState.collectAsStateWithLifecycle()
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background
            ) {
                HomeScreen(
                    dashboardUiState = dashboardUiState,
                    settingsUiState = settingsUiState,
                    onRetrySettings = settingsViewModel::refresh,
                    aiAvailable = application.isAiConfigured,
                    dailyIntakeUiState = dailyIntakeUiState,
                    onSelectRoute = dashboardViewModel::selectRoute,
                    onSelectDate = dailyIntakeViewModel::selectDate,
                    onAddDailyIntake = dailyIntakeViewModel::showNewEntry,
                    onEditDailyIntake = dailyIntakeViewModel::editRecord,
                    onSaveDailyIntake = dailyIntakeViewModel::save,
                    onDismissDailyIntakeEditor = dailyIntakeViewModel::dismissEditor,
                    onClearDailyIntakeFeedback = dailyIntakeViewModel::clearFeedback,
                    onRetryDashboard = dashboardViewModel::refresh,
                    onLogout = dashboardViewModel::logout,
                    fruitNutritionUiState = fruitNutritionUiState,
                    onFruitQueryChanged = fruitNutritionViewModel::queryChanged,
                    onSearchFruit = fruitNutritionViewModel::search,
                    onRetryFruit = fruitNutritionViewModel::retry,
                    nutriCoachUiState = nutriCoachUiState,
                    onPlanningNeedChanged = nutriCoachViewModel::planningNeedChanged,
                    onGeneratePlan = nutriCoachViewModel::generatePlan,
                    onCancelPlan = nutriCoachViewModel::cancelGeneration,
                    onRetryPlan = nutriCoachViewModel::retry,
                    onConfirmPlan = nutriCoachViewModel::confirmDraft,
                    onAbandonPlan = nutriCoachViewModel::abandonDraft,
                    onAbandonConfirmedPlan = nutriCoachViewModel::abandonConfirmedPlan,
                    onGenerateMotivationalMessage = nutriCoachViewModel::generateMotivationalMessage,
                    onEditQuestionnaire = {
                        val intent = Intent(context, QuestionnaireActivity::class.java)
                        intent.putExtra("USER_ID", sessionState.userId.toString())
                        intent.putExtra("PHONE_NUMBER", phoneNumber)
                        context.startActivity(intent)
                    }
                )
            }
    }
}

@Composable
internal fun HomeSessionGate(
    snapshot: SessionSnapshot,
    onSessionUnavailable: () -> Unit,
    protectedContent: @Composable (SessionState.LoggedIn) -> Unit
) {
    when (val sessionState = snapshot.sessionState) {
        SessionState.Loading -> HomeLoadingScreen()
        SessionState.LoggedOut, is SessionState.ReadError -> {
            HomeLoadingScreen()
            LaunchedEffect(sessionState) { onSessionUnavailable() }
        }
        is SessionState.LoggedIn -> protectedContent(sessionState)
    }
}

@Composable
private fun HomeLoadingScreen() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    dashboardUiState: DashboardUiState,
    dailyIntakeUiState: DailyIntakeUiState,
    onSelectRoute: (AppRoute) -> Unit,
    onSelectDate: (LocalDate) -> Unit,
    onAddDailyIntake: () -> Unit,
    onEditDailyIntake: (Long) -> Unit,
    onSaveDailyIntake: (DailyIntakeFormInput) -> Unit,
    onDismissDailyIntakeEditor: () -> Unit,
    onClearDailyIntakeFeedback: () -> Unit,
    onRetryDashboard: () -> Unit,
    onLogout: () -> Unit,
    onEditQuestionnaire: () -> Unit,
    fruitNutritionUiState: FruitNutritionUiState = FruitNutritionUiState(),
    onFruitQueryChanged: (String) -> Unit = {},
    onSearchFruit: () -> Unit = {},
    onRetryFruit: () -> Unit = {},
    nutriCoachUiState: NutriCoachUiState = NutriCoachUiState(),
    onPlanningNeedChanged: (String) -> Unit = {},
    onGeneratePlan: () -> Unit = {},
    onCancelPlan: () -> Unit = {},
    onRetryPlan: () -> Unit = {},
    onConfirmPlan: () -> Unit = {},
    onAbandonPlan: () -> Unit = {},
    onAbandonConfirmedPlan: () -> Unit = {},
    onGenerateMotivationalMessage: () -> Unit = {},
    settingsUiState: SettingsUiState = SettingsUiState(),
    onRetrySettings: () -> Unit = {},
    aiAvailable: Boolean = true
) {
    val initialRoute = remember(dashboardUiState.userId) { dashboardUiState.selectedRoute }

    HomeNavigationScaffold(
        initialRoute = initialRoute,
        onRouteChanged = onSelectRoute
    ) { selectedItem, navigateTo, navigateBack, contentModifier ->
        when (selectedItem) {
            AppRoute.DAILY_INTAKE -> {
                Box(modifier = contentModifier) {
                    DailyIntakeScreen(
                        uiState = dailyIntakeUiState,
                        onBack = navigateBack,
                        onSelectDate = onSelectDate,
                        onAdd = onAddDailyIntake,
                        onEdit = onEditDailyIntake,
                        onSave = onSaveDailyIntake,
                        onDismissEditor = onDismissDailyIntakeEditor,
                        onClearFeedback = onClearDailyIntakeFeedback
                    )
                }
            }
            AppRoute.SETTINGS -> {
                Box(modifier = contentModifier) {
                    SettingsScreen(
                        uiState = settingsUiState,
                        aiAvailable = aiAvailable,
                        isLoggingOut = dashboardUiState.isLoggingOut,
                        onRetry = onRetrySettings,
                        onLogout = onLogout
                    )
                }
            }
            AppRoute.NUTRI_COACH -> {
                NutriCoachScreen(
                    coachUiState = nutriCoachUiState,
                    aiAvailable = aiAvailable,
                    fruitNutritionUiState = fruitNutritionUiState,
                    onFruitQueryChanged = onFruitQueryChanged,
                    onSearchFruit = onSearchFruit,
                    onRetryFruit = onRetryFruit,
                    onPlanningNeedChanged = onPlanningNeedChanged,
                    onGeneratePlan = onGeneratePlan,
                    onCancelPlan = onCancelPlan,
                    onRetryPlan = onRetryPlan,
                    onConfirmPlan = onConfirmPlan,
                    onAbandonPlan = onAbandonPlan,
                    onAbandonConfirmedPlan = onAbandonConfirmedPlan,
                    onGenerateMotivationalMessage = onGenerateMotivationalMessage,
                    modifier = contentModifier
                )
            }
            AppRoute.INSIGHTS -> {
                Box(modifier = contentModifier) {
                    InsightsScreen(
                        uiState = dashboardUiState,
                        onRetry = onRetryDashboard,
                        onNavigateToNutriCoach = { navigateTo(AppRoute.NUTRI_COACH) }
                    )
                }
            }
            else -> {
                var scoreExplanationExpanded by rememberSaveable { mutableStateOf(false) }
                val scoreProgress by animateFloatAsState(
                    targetValue = (dashboardUiState.foodQualityScore / 100f).coerceIn(0f, 1f),
                    label = "Home food score"
                )
                Column(
                    modifier = contentModifier
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 24.dp, vertical = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(24.dp)
                ) {
                    NutriPageHeader(
                        title = "Hello, User ${dashboardUiState.userId}",
                        subtitle = "A little care, every day.",
                        trailing = {
                            OutlinedIconButton(onClick = onEditQuestionnaire) {
                                Icon(Icons.Default.Edit, contentDescription = "Edit questionnaire")
                            }
                        }
                    )

                    dashboardUiState.errorMessage?.let { message ->
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(message, color = MaterialTheme.colorScheme.onErrorContainer)
                                TextButton(onClick = onRetryDashboard) { Text("Retry") }
                            }
                        }
                    }

                    NutriCard {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Column(modifier = Modifier.weight(1.4f)) {
                                Text(
                                    text = "Eat well.\nFeel better.",
                                    style = MaterialTheme.typography.headlineMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 28.sp,
                                    lineHeight = 32.sp,
                                    maxLines = 2
                                )
                                Spacer(Modifier.height(12.dp))
                                Text(
                                    text = "Make room for a balanced day.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Image(
                                painter = painterResource(R.drawable.food_plate),
                                contentDescription = "A balanced food plate",
                                modifier = Modifier.weight(0.8f).aspectRatio(1f)
                            )
                        }
                        Spacer(Modifier.height(24.dp))
                        NutriPrimaryButton(
                            text = "Log or review daily intake",
                            onClick = { navigateTo(AppRoute.DAILY_INTAKE) },
                            modifier = Modifier.fillMaxWidth().testTag("home_daily_intake")
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(Modifier.weight(1f)) {
                            NutriSectionTitle(title = "My Score", subtitle = "Your food quality at a glance")
                        }
                        TextButton(onClick = { navigateTo(AppRoute.INSIGHTS) }) {
                            Text("See all scores")
                        }
                    }
                    when {
                        dashboardUiState.isLoading -> Box(
                            modifier = Modifier.fillMaxWidth().height(120.dp),
                            contentAlignment = Alignment.Center
                        ) { CircularProgressIndicator() }
                        dashboardUiState.patient == null -> NutriEmptyState(
                            title = "Your profile is unavailable",
                            body = "No patient profile is available for this account."
                        )
                        else -> NutriCard(highlighted = true) {
                            Text(
                                text = "Your Food Quality score",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text("Sample baseline · separate from your daily food log",
                                style = MaterialTheme.typography.bodySmall)
                            Row(
                                verticalAlignment = Alignment.Bottom,
                                modifier = Modifier.padding(vertical = 12.dp)
                            ) {
                                Text(
                                    text = dashboardUiState.foodQualityScore.toString(),
                                    style = MaterialTheme.typography.displaySmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = " / 100",
                                    style = MaterialTheme.typography.titleMedium,
                                    modifier = Modifier.padding(bottom = 5.dp)
                                )
                            }
                            LinearProgressIndicator(
                                progress = { scoreProgress },
                                modifier = Modifier.fillMaxWidth().height(6.dp),
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                trackColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.2f)
                            )
                        }
                    }

                    NutriCard {
                        Text(
                            text = "Your daily journal",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = dailyIntakeUiState.selectedDate.toString(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp, bottom = 16.dp)
                        )
                        when {
                            dailyIntakeUiState.isLoading -> LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            dailyIntakeUiState.errorMessage != null -> Text(
                                text = dailyIntakeUiState.errorMessage,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodyMedium
                            )
                            else -> Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        text = dailyIntakeUiState.records.size.toString(),
                                        style = MaterialTheme.typography.headlineMedium
                                    )
                                    Text("Recorded entries", style = MaterialTheme.typography.bodySmall)
                                }
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        text = "${dailyIntakeUiState.records.sumOf { it.waterMl }} mL",
                                        style = MaterialTheme.typography.headlineSmall
                                    )
                                    Text("Water recorded", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }

                    NutriCard {
                        TextButton(
                            onClick = { scoreExplanationExpanded = !scoreExplanationExpanded },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Text(
                                text = "What is the Food Quality Score?",
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.weight(1f)
                            )
                            Icon(
                                imageVector = if (scoreExplanationExpanded) Icons.Default.KeyboardArrowUp
                                else Icons.Default.KeyboardArrowDown,
                                contentDescription = if (scoreExplanationExpanded) "Collapse score explanation"
                                else "Expand score explanation"
                            )
                        }
                        AnimatedVisibility(visible = scoreExplanationExpanded) {
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text(
                                    text = "This demo score is a fictional baseline from your sample profile. Adding daily intake records does not recalculate it. Use your intake history to review what you have actually logged.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = "This personalized measurement considers various food groups including vegetables, fruits, whole grains, and proteins to give you practical insights for making healthier food choices.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}
