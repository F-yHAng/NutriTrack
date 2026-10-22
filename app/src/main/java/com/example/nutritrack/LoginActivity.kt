package com.example.nutritrack

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.nutritrack.data.AuthMode
import com.example.nutritrack.data.AuthUiState

@Composable
fun LoginScreen(
    uiState: AuthUiState,
    onUserIdChanged: (Int) -> Unit,
    onLogin: (String) -> Unit,
    onShowRegistration: () -> Unit,
    onShowLogin: () -> Unit,
    onValidateRegistrationCredentials: (String) -> Unit,
    onRegister: (phone: String, name: String, password: String, confirmation: String) -> Unit,
    onRetry: () -> Unit,
    onClearError: () -> Unit,
    onCancel: () -> Unit
) {
    if (uiState.isLoadingUsers) {
        Box(
            modifier = Modifier.fillMaxWidth().padding(32.dp),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator()
        }
        return
    }

    when (uiState.mode) {
        AuthMode.LOGIN -> LoginForm(
            uiState = uiState,
            onUserIdChanged = onUserIdChanged,
            onLogin = onLogin,
            onNavigateToRegister = onShowRegistration,
            onRetry = onRetry,
            onClearError = onClearError,
            onCancel = onCancel
        )
        AuthMode.REGISTER -> RegisterScreen(
            uiState = uiState,
            onUserIdChanged = onUserIdChanged,
            onValidateCredentials = onValidateRegistrationCredentials,
            onRegister = onRegister,
            onNavigateToLogin = onShowLogin,
            onClearError = onClearError
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LoginForm(
    uiState: AuthUiState,
    onUserIdChanged: (Int) -> Unit,
    onLogin: (String) -> Unit,
    onNavigateToRegister: () -> Unit,
    onRetry: () -> Unit,
    onClearError: () -> Unit,
    onCancel: () -> Unit
) {
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = "Log in",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        Text(
            text = "Your nutrition journey, all in one place.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 20.dp)
        )

        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = !expanded },
            modifier = Modifier.fillMaxWidth()
        ) {
            OutlinedTextField(
                value = uiState.selectedUserId.takeIf { it > 0 }?.toString().orEmpty(),
                onValueChange = {},
                readOnly = true,
                label = { Text("Demo profile ID", fontWeight = FontWeight.Bold) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                modifier = Modifier.fillMaxWidth().menuAnchor().testTag("auth_user_id"),
                shape = RoundedCornerShape(14.dp)
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                uiState.availableUserIds.forEach { id ->
                    DropdownMenuItem(
                        text = { Text(id.toString()) },
                        onClick = {
                            password = ""
                            onUserIdChanged(id)
                            expanded = false
                        },
                        modifier = Modifier.testTag("auth_user_option_$id")
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        when {
            uiState.isCheckingUser -> CircularProgressIndicator(Modifier.size(20.dp))
            uiState.selectedUserId > 0 && uiState.selectedUserIsRegistered -> {
                OutlinedTextField(
                    value = password,
                    onValueChange = {
                        password = it
                        onClearError()
                    },
                    modifier = Modifier.fillMaxWidth().testTag("auth_password"),
                    label = { Text("Password", fontWeight = FontWeight.Bold) },
                    placeholder = { Text("Enter your password") },
                    visualTransformation = if (showPassword) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    singleLine = true,
                    trailingIcon = {
                        TextButton(onClick = { showPassword = !showPassword }) {
                            Text(if (showPassword) "Hide" else "Show")
                        }
                    },
                    shape = RoundedCornerShape(14.dp)
                )
            }
            uiState.selectedUserId > 0 -> Text(
                text = "This ID has not been registered yet. Please click 'Register' to set up your account.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 12.dp)
            )
        }

        Spacer(Modifier.height(16.dp))
        Text(
            text = "First visit? Register a sample profile with fictional details and a password you choose. Your records are saved locally on this device.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (uiState.availableUserIds.isEmpty()) {
            Text("No User IDs available. Please ensure the app is properly initialized.", color = MaterialTheme.colorScheme.error)
            TextButton(onClick = onRetry) { Text("Retry") }
        } else {
            Text(
                "${uiState.availableUserIds.size} User IDs available in database",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 4.dp)
            )
        }
        uiState.errorMessage?.let {
            Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(8.dp))
        }

        Spacer(Modifier.height(16.dp))
        Button(
            onClick = { onLogin(password) },
            enabled = !uiState.isSubmitting && !uiState.isCheckingUser &&
                uiState.selectedUserIsRegistered,
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("auth_login_submit"),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
            shape = RoundedCornerShape(16.dp)
        ) {
            if (uiState.isSubmitting) {
                CircularProgressIndicator(Modifier.size(20.dp), color = MaterialTheme.colorScheme.onPrimary)
            } else {
                Text("Continue", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        }

        Spacer(Modifier.height(12.dp))
        OutlinedButton(
            onClick = onNavigateToRegister,
            enabled = !uiState.isSubmitting,
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Text("Register", fontWeight = FontWeight.Bold)
        }
        TextButton(
            onClick = onCancel,
            enabled = !uiState.isSubmitting,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        ) { Text("Cancel") }
    }
}
