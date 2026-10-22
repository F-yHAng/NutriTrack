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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.nutritrack.data.AuthUiState
import com.example.nutritrack.data.PasswordValidator
import com.example.nutritrack.data.PasswordStrength
import com.example.nutritrack.data.RegistrationCredentialStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RegisterScreen(
    uiState: AuthUiState,
    onUserIdChanged: (Int) -> Unit,
    onValidateCredentials: (String) -> Unit,
    onRegister: (phone: String, name: String, password: String, confirmation: String) -> Unit,
    onNavigateToLogin: () -> Unit,
    onClearError: () -> Unit
) {
    var phoneNumber by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var showConfirmPassword by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }
    val passwordStrength = remember(password) {
        password.takeIf(String::isNotEmpty)?.let(PasswordValidator::getPasswordStrength)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.Start
    ) {
        Text("Register", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(
            "A little about you. A better daily routine.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp)
        )
        Spacer(Modifier.height(24.dp))
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
                modifier = Modifier.fillMaxWidth().menuAnchor(),
                shape = RoundedCornerShape(14.dp)
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                uiState.unregisteredUserIds.forEach { id ->
                    DropdownMenuItem(
                        text = { Text(id.toString()) },
                        onClick = {
                            phoneNumber = ""
                            onUserIdChanged(id)
                            expanded = false
                        }
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        if (uiState.selectedUserId in 1..9 && uiState.selectedUserId in uiState.unregisteredUserIds) {
            val samplePhone = (2025550100L + uiState.selectedUserId).toString()
            Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Try a sample profile", style = MaterialTheme.typography.titleSmall)
                    Text("Profile ${uiState.selectedUserId} uses the fictional number $samplePhone. Keep your real contact details private.",
                        style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = {
                        phoneNumber = samplePhone
                        onClearError()
                        onValidateCredentials(samplePhone)
                    }, enabled = !uiState.isSubmitting, modifier = Modifier.testTag("register_sample_phone")) {
                        Text("Use sample number")
                    }
                }
            }
        } else if (uiState.unregisteredUserIds.isEmpty()) {
            Text("All sample profiles are registered. Return to Login and use an existing account.", style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = phoneNumber,
            onValueChange = { value ->
                if (value.length <= 15 && value.all(Char::isDigit)) {
                    phoneNumber = value
                    onClearError()
                    onValidateCredentials(value)
                }
            },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Phone Number", fontWeight = FontWeight.Bold) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            shape = RoundedCornerShape(14.dp)
        )
        CredentialFeedback(uiState.credentialStatus)

        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = name,
            onValueChange = { value ->
                if (value.length <= 50) {
                    name = value
                    onClearError()
                }
            },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Name", fontWeight = FontWeight.Bold) },
            singleLine = true,
            shape = RoundedCornerShape(14.dp)
        )

        Spacer(Modifier.height(16.dp))
        PasswordField(
            label = "Password",
            value = password,
            visible = showPassword,
            onVisibleChange = { showPassword = it },
            onValueChange = {
                password = it
                onClearError()
            }
        )
        passwordStrength?.let {
            Text(
                text = "Password strength: ${it.label}",
                color = when (it) {
                    PasswordStrength.WEAK -> MaterialTheme.colorScheme.error
                    PasswordStrength.MEDIUM -> MaterialTheme.colorScheme.onSurfaceVariant
                    PasswordStrength.STRONG -> MaterialTheme.colorScheme.primary
                },
                fontSize = 12.sp,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
            )
        }

        Spacer(Modifier.height(16.dp))
        PasswordField(
            label = "Confirm Password",
            value = confirmPassword,
            visible = showConfirmPassword,
            onVisibleChange = { showConfirmPassword = it },
            onValueChange = {
                confirmPassword = it
                onClearError()
            }
        )

        Spacer(Modifier.height(16.dp))
        Text(
            text = "These profiles are fictional. Choose your own password; no shared login or default password is provided.",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        uiState.errorMessage?.let {
            Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(8.dp))
        }

        Spacer(Modifier.height(16.dp))
        Button(
            onClick = { onRegister(phoneNumber, name, password, confirmPassword) },
            enabled = !uiState.isSubmitting &&
                uiState.credentialStatus == RegistrationCredentialStatus.VALID,
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            if (uiState.isSubmitting) {
                CircularProgressIndicator(Modifier.size(20.dp), color = MaterialTheme.colorScheme.onPrimary)
            } else {
                Text("Register", fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(
            onClick = onNavigateToLogin,
            enabled = !uiState.isSubmitting,
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Text("Login", fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun CredentialFeedback(status: RegistrationCredentialStatus) {
    if (status == RegistrationCredentialStatus.IDLE) return
    val (message, color) = when (status) {
        RegistrationCredentialStatus.VALIDATING -> "Validating credentials..." to MaterialTheme.colorScheme.onSurfaceVariant
        RegistrationCredentialStatus.VALID -> "✓ Credentials verified! You can register." to MaterialTheme.colorScheme.primary
        RegistrationCredentialStatus.INVALID ->
            "✗ User ID and phone number do not match or the account is already registered." to MaterialTheme.colorScheme.error
        RegistrationCredentialStatus.ERROR ->
            "✗ Unable to validate credentials. Please try again." to MaterialTheme.colorScheme.error
        RegistrationCredentialStatus.IDLE -> return
    }
    Surface(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        color = color.copy(alpha = 0.1f),
        shape = RoundedCornerShape(14.dp)
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (status == RegistrationCredentialStatus.VALIDATING) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text(message, fontSize = 12.sp, color = color)
        }
    }
}

@Composable
private fun PasswordField(
    label: String,
    value: String,
    visible: Boolean,
    onVisibleChange: (Boolean) -> Unit,
    onValueChange: (String) -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label, fontWeight = FontWeight.Bold) },
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        singleLine = true,
        trailingIcon = {
            TextButton(onClick = { onVisibleChange(!visible) }) {
                Text(if (visible) "Hide" else "Show")
            }
        },
        shape = RoundedCornerShape(14.dp)
    )
}
