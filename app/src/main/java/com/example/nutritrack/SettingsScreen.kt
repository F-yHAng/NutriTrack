package com.example.nutritrack

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.nutritrack.data.SettingsUiState
import com.example.nutritrack.ui.components.NutriCard

@Composable
fun SettingsScreen(
    uiState: SettingsUiState,
    aiAvailable: Boolean,
    isLoggingOut: Boolean = false,
    onRetry: () -> Unit,
    onLogout: () -> Unit
) {
    var showLogoutDialog by rememberSaveable { mutableStateOf(false) }
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Settings", style = MaterialTheme.typography.headlineLarge)
            Text("Your account, your data.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        NutriCard {
            Text("Local account", style = MaterialTheme.typography.titleLarge)
            when {
                uiState.isLoading -> {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    Text("Loading account details…")
                }
                uiState.errorMessage != null -> {
                    Text(uiState.errorMessage, color = MaterialTheme.colorScheme.error)
                    OutlinedButton(onClick = onRetry, modifier = Modifier.testTag("settings_retry")) { Text("Try again") }
                }
                else -> uiState.account?.let { account ->
                    AccountDetail("Name", account.name.ifBlank { "User ${account.userId}" })
                    AccountDetail("User ID", account.userId.toString())
                    AccountDetail("Phone number", account.phoneNumber)
                }
            }
        }
        NutriCard {
            Text("Data & connectivity", style = MaterialTheme.typography.titleLarge)
            AccountDetail("Nutrition tracking", "Available offline")
            AccountDetail("AI coaching", if (aiAvailable) "Configured · requires internet" else "Not configured in this build")
            Text("Your questionnaire, intake records and saved plans stay on this device. Cloud sync is not available.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("When you request AI coaching, your request and selected nutrition evidence are sent to the AI service. Use fictional data when exploring this demo.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        NutriCard {
            Text("About this demo", style = MaterialTheme.typography.titleLarge)
            Text("NutriTrack · Android nutrition journal", style = MaterialTheme.typography.bodyLarge)
            Text("Sample profiles and baseline scores are fictional. General nutrition guidance only; not medical advice.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Clinician access is unavailable in this version.", style = MaterialTheme.typography.bodySmall)
        }
        OutlinedButton(
            onClick = { showLogoutDialog = true }, enabled = !isLoggingOut,
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("settings_logout")
        ) { Text(if (isLoggingOut) "Signing out…" else "Log out") }
    }
    if (showLogoutDialog) {
        AlertDialog(
            onDismissRequest = { showLogoutDialog = false },
            title = { Text("Log out?") },
            text = { Text("Your records stay on this device. Sign in with the same account to return to them.") },
            confirmButton = { TextButton(onClick = { showLogoutDialog = false; onLogout() }) { Text("Log out") } },
            dismissButton = { TextButton(onClick = { showLogoutDialog = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun AccountDetail(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}
