package com.example.nutritrack

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.nutritrack.data.AuthNavigation
import com.example.nutritrack.data.AuthUiState
import com.example.nutritrack.data.AuthViewModel
import com.example.nutritrack.data.SessionSnapshot
import com.example.nutritrack.data.SessionState
import com.example.nutritrack.ui.theme.NutritrackTheme
import com.example.nutritrack.ui.components.NutriCard
import com.example.nutritrack.ui.components.NutriPrimaryButton
import kotlinx.coroutines.launch

class WelcomeActivity : ComponentActivity() {
    private val authViewModel: AuthViewModel by viewModels {
        val application = applicationContext as NutrackApplication
        AuthViewModel.Factory(application.repository, application.sessionManager)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val application = applicationContext as NutrackApplication
        setContent {
            NutritrackTheme {
                val snapshot by application.sessionManager.state.collectAsStateWithLifecycle(
                    initialValue = SessionSnapshot(SessionState.Loading)
                )
                val authUiState by authViewModel.uiState.collectAsStateWithLifecycle()
                AuthNavigationEffect(
                    navigation = authUiState.navigation,
                    onHandled = authViewModel::navigationHandled
                )
                WelcomeRoute(
                    snapshot = snapshot,
                    authUiState = authUiState,
                    onResolveLoggedIn = authViewModel::resolveLoggedInSession,
                    onUserIdChanged = authViewModel::selectUser,
                    onLogin = authViewModel::login,
                    onShowRegistration = authViewModel::showRegistration,
                    onShowLogin = authViewModel::showLogin,
                    onValidateRegistrationCredentials =
                        authViewModel::validateRegistrationCredentials,
                    onRegister = authViewModel::register,
                    onRetry = authViewModel::retryLoadingUsers,
                    onClearError = authViewModel::clearError
                )
            }
        }
    }
}

@Composable
private fun AuthNavigationEffect(
    navigation: AuthNavigation?,
    onHandled: (Long) -> Unit
) {
    val context = LocalContext.current
    LaunchedEffect(navigation?.eventId) {
        val event = navigation ?: return@LaunchedEffect
        val destination = if (event.hasCompletedQuestionnaire) {
            Intent(context, HomeActivity::class.java).apply {
                putExtra("NAV", event.lastPage.storedValue)
            }
        } else {
            Intent(context, QuestionnaireActivity::class.java)
        }
        destination.putExtra("USER_ID", event.userId.toString())
        destination.putExtra("PHONE_NUMBER", event.phoneNumber)
        context.startActivity(destination)
        onHandled(event.eventId)
        (context as? ComponentActivity)?.finish()
    }
}

internal sealed interface WelcomeStartupAction {
    data object Wait : WelcomeStartupAction
    data object ShowWelcome : WelcomeStartupAction
    data class ResolveLoggedIn(val userId: Int) : WelcomeStartupAction
    data class ShowConfigurationError(val message: String) : WelcomeStartupAction
}

internal fun decideWelcomeStartup(snapshot: SessionSnapshot): WelcomeStartupAction =
    when (val state = snapshot.sessionState) {
        SessionState.Loading -> WelcomeStartupAction.Wait
        SessionState.LoggedOut -> WelcomeStartupAction.ShowWelcome
        is SessionState.LoggedIn -> WelcomeStartupAction.ResolveLoggedIn(state.userId)
        is SessionState.ReadError -> WelcomeStartupAction.ShowConfigurationError(state.message)
    }

@Composable
private fun WelcomeRoute(
    snapshot: SessionSnapshot,
    authUiState: AuthUiState,
    onResolveLoggedIn: (Int, com.example.nutritrack.data.AppRoute) -> Unit,
    onUserIdChanged: (Int) -> Unit,
    onLogin: (String) -> Unit,
    onShowRegistration: () -> Unit,
    onShowLogin: () -> Unit,
    onValidateRegistrationCredentials: (String) -> Unit,
    onRegister: (String, String, String, String) -> Unit,
    onRetry: () -> Unit,
    onClearError: () -> Unit
) {
    when (val action = decideWelcomeStartup(snapshot)) {
        WelcomeStartupAction.Wait -> StartupLoadingScreen()
        WelcomeStartupAction.ShowWelcome -> WelcomeScreen(
            authUiState = authUiState,
            onUserIdChanged = onUserIdChanged,
            onLogin = onLogin,
            onShowRegistration = onShowRegistration,
            onShowLogin = onShowLogin,
            onValidateRegistrationCredentials = onValidateRegistrationCredentials,
            onRegister = onRegister,
            onRetry = onRetry,
            onClearError = onClearError
        )
        is WelcomeStartupAction.ShowConfigurationError -> {
            WelcomeScreen(
                authUiState = authUiState,
                sessionError = action.message,
                onUserIdChanged = onUserIdChanged,
                onLogin = onLogin,
                onShowRegistration = onShowRegistration,
                onShowLogin = onShowLogin,
                onValidateRegistrationCredentials = onValidateRegistrationCredentials,
                onRegister = onRegister,
                onRetry = onRetry,
                onClearError = onClearError
            )
        }
        is WelcomeStartupAction.ResolveLoggedIn -> {
            StartupLoadingScreen()
            LaunchedEffect(action.userId, snapshot.lastPage) {
                onResolveLoggedIn(action.userId, snapshot.lastPage)
            }
        }
    }
}

@Composable
private fun StartupLoadingScreen() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WelcomeScreen(
    authUiState: AuthUiState,
    sessionError: String? = null,
    onUserIdChanged: (Int) -> Unit,
    onLogin: (String) -> Unit,
    onShowRegistration: () -> Unit,
    onShowLogin: () -> Unit,
    onValidateRegistrationCredentials: (String) -> Unit,
    onRegister: (String, String, String, String) -> Unit,
    onRetry: () -> Unit,
    onClearError: () -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true
    )
    var showBottomSheet by remember { mutableStateOf(false) }
    
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("NutriTrack", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
                Text("Everyday wellbeing", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Box(
                modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.extraLarge)
                    .background(Brush.linearGradient(listOf(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.secondaryContainer)))
            ) {
                Column(Modifier.padding(24.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "A little better.\nEvery day.",
                            style = MaterialTheme.typography.displaySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.weight(1f)
                        )
                        Image(
                            painterResource(R.drawable.food_plate), contentDescription = "A balanced food plate",
                            modifier = Modifier.size(108.dp)
                        )
                    }
                    Spacer(Modifier.height(20.dp))
                    Text(
                        "Make sense of your meals. Build habits that feel good.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                listOf("Daily food journal", "Personal insights", "Thoughtful AI guidance").forEach { label ->
                    Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
            }
            Spacer(Modifier.height(28.dp))

            NutriPrimaryButton(
                text = "Login", onClick = { showBottomSheet = true },
                modifier = Modifier.fillMaxWidth().testTag("welcome_login")
            )
            Text(
                "For registered NutriTrack users",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 10.dp, bottom = 28.dp)
            )
            sessionError?.let { message ->
                Text(message, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(bottom = 16.dp))
            }

            NutriCard {
                    Text("A mindful starting point", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = "This app provides general health and nutrition information for educational purposes only. It is not intended as medical advice, diagnosis, or treatment. Always consult a qualified healthcare professional before making any changes to your diet, exercise, or health regimen.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Use this app at your own risk.",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "For personalised advice, consult a qualified dietitian or healthcare professional.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
            }
            Text(
                text = "NutriTrack · Educational demo",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 28.dp)
            )
        }
    }

    if (showBottomSheet) {
        ModalBottomSheet(
            onDismissRequest = { showBottomSheet = false },
            modifier = Modifier.fillMaxHeight(),
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp)
        ) {
            LoginScreen(
                uiState = authUiState,
                onUserIdChanged = onUserIdChanged,
                onLogin = onLogin,
                onShowRegistration = onShowRegistration,
                onShowLogin = onShowLogin,
                onValidateRegistrationCredentials = onValidateRegistrationCredentials,
                onRegister = onRegister,
                onRetry = onRetry,
                onClearError = onClearError,
                onCancel = {
                    coroutineScope.launch { sheetState.hide() }.invokeOnCompletion {
                        if (!sheetState.isVisible) showBottomSheet = false
                    }
                }
            )
        }
    }
}
