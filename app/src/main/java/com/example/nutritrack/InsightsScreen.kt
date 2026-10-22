package com.example.nutritrack

import android.content.Intent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.nutritrack.data.CategoryScore
import com.example.nutritrack.data.DashboardUiState
import com.example.nutritrack.ui.components.NutriCard
import com.example.nutritrack.ui.components.NutriEmptyState
import com.example.nutritrack.ui.components.NutriPageHeader
import com.example.nutritrack.ui.components.NutriPrimaryButton
import com.example.nutritrack.ui.components.NutriSectionTitle

@Composable
fun InsightsScreen(
    uiState: DashboardUiState,
    onRetry: () -> Unit,
    onNavigateToNutriCoach: () -> Unit = {}
) {
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        NutriPageHeader(
            title = "Insights",
            subtitle = "Understand your eating patterns."
        )
        when {
            uiState.isLoading -> Box(
                modifier = Modifier.fillMaxWidth().height(280.dp),
                contentAlignment = Alignment.Center
            ) { CircularProgressIndicator() }
            uiState.patient == null -> {
                NutriEmptyState(
                    title = "Your profile is unavailable",
                    body = uiState.errorMessage ?: "No patient profile is available for this account."
                )
                NutriPrimaryButton(text = "Retry", onClick = onRetry, modifier = Modifier.fillMaxWidth())
            }
            else -> {
                val totalProgress by animateFloatAsState(
                    targetValue = (uiState.foodQualityScore / 100f).coerceIn(0f, 1f),
                    label = "Total food quality score"
                )
                NutriCard(highlighted = true) {
                    Text("Total Food Quality Score", style = MaterialTheme.typography.titleMedium)
                    Text("Sample baseline · not recalculated from your food log", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(
                        modifier = Modifier.padding(vertical = 12.dp),
                        verticalAlignment = Alignment.Bottom
                    ) {
                        Text(
                            text = uiState.foodQualityScore.toString(),
                            style = MaterialTheme.typography.displayMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = " / 100",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(bottom = 6.dp)
                        )
                    }
                    LinearProgressIndicator(
                        progress = { totalProgress },
                        modifier = Modifier.fillMaxWidth().height(6.dp),
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        trackColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.2f)
                    )
                    Text(
                        text = "A snapshot of your food quality, not a diagnosis.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 16.dp)
                    )
                }

                NutriSectionTitle(title = "Food groups", subtitle = "See where your score comes from")
                NutriCard {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        uiState.categoryScores.forEachIndexed { index, category ->
                            FoodGroupScoreRow(category)
                            if (index < uiState.categoryScores.lastIndex) {
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                            }
                        }
                    }
                }

                NutriSectionTitle(
                    title = "Daily intake history",
                    subtitle = "Your recorded intake, day by day"
                )
                uiState.intakeErrorMessage?.let { message ->
                    Text(text = message, color = MaterialTheme.colorScheme.error)
                }
                when {
                    uiState.isHistoryLoading -> Box(
                        modifier = Modifier.fillMaxWidth().height(80.dp),
                        contentAlignment = Alignment.Center
                    ) { CircularProgressIndicator() }
                    uiState.dailyIntakeSummaries.isEmpty() -> NutriEmptyState(
                        title = "Your journal starts here",
                        body = "No daily intake has been recorded yet."
                    )
                    else -> uiState.dailyIntakeSummaries.forEach { summary ->
                        NutriCard {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = summary.localDate.toString(),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    text = "${summary.recordCount} entries",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            HorizontalDivider(
                                modifier = Modifier.padding(vertical = 16.dp),
                                color = MaterialTheme.colorScheme.outlineVariant
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                IntakeSummaryMetric(
                                    label = "Vegetables",
                                    value = summary.vegetablesServings.toString(),
                                    unit = "servings",
                                    modifier = Modifier.weight(1f)
                                )
                                IntakeSummaryMetric(
                                    label = "Fruit",
                                    value = summary.fruitServings.toString(),
                                    unit = "servings",
                                    modifier = Modifier.weight(1f)
                                )
                                IntakeSummaryMetric(
                                    label = "Water",
                                    value = summary.waterMl.toString(),
                                    unit = "mL",
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }

                NutriCard {
                    NutriSectionTitle(title = "Turn insight into action", subtitle = "Explore practical ideas in NutriCoach.")
                    Spacer(Modifier.height(20.dp))
                    NutriPrimaryButton(
                        text = "Improve my diet",
                        onClick = onNavigateToNutriCoach,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedButton(
                        onClick = {
                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(
                                    Intent.EXTRA_TEXT,
                                    "Hi, I just got a HEIFA score of ${uiState.foodQualityScore}!"
                                )
                            }
                            context.startActivity(Intent.createChooser(shareIntent, "Share your food score via"))
                        },
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp).heightIn(min = 52.dp),
                        shape = MaterialTheme.shapes.medium
                    ) {
                        Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Share with someone")
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun FoodGroupScoreRow(category: CategoryScore) {
    val scoreProgress by animateFloatAsState(
        targetValue = (category.score / category.maxScore).coerceIn(0f, 1f),
        label = "Food group score"
    )
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = category.name,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = "${category.score} / ${category.maxScore.toInt()}",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
        }
        LinearProgressIndicator(
            progress = { scoreProgress },
            modifier = Modifier.fillMaxWidth().height(5.dp),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceVariant
        )
    }
}

@Composable
private fun IntakeSummaryMetric(label: String, value: String, unit: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(text = label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text = value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text(text = unit, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
