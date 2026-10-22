package com.example.nutritrack

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun ClinicianScreen(onNavigateBack: () -> Unit = {}) {
    // A client-side shared password cannot authorize access to other users' data.
    // Keep this route closed until a backend verifies clinician roles and scope.
    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        Text("Clinician access unavailable", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(16.dp))
        Text(
            "This demo does not provide clinician authorization. " +
                "Access to patient statistics requires a service that verifies your role."
        )
        TextButton(onClick = onNavigateBack) { Text("Back") }
    }
}
