package com.example.nutritrack

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.nutritrack.data.*
import com.example.nutritrack.ui.components.NutriCard
import com.example.nutritrack.ui.components.NutriSectionTitle

@Composable
fun NutriCoachScreen(
    coachUiState: NutriCoachUiState,
    fruitNutritionUiState: FruitNutritionUiState,
    onFruitQueryChanged: (String) -> Unit,
    onSearchFruit: () -> Unit,
    onRetryFruit: () -> Unit,
    onPlanningNeedChanged: (String) -> Unit,
    onGeneratePlan: () -> Unit,
    onCancelPlan: () -> Unit,
    onRetryPlan: () -> Unit,
    onConfirmPlan: () -> Unit = {},
    onAbandonPlan: () -> Unit = {},
    onAbandonConfirmedPlan: () -> Unit = {},
    onGenerateMotivationalMessage: () -> Unit,
    modifier: Modifier = Modifier,
    aiAvailable: Boolean = true
) {
    val keyboardController = LocalSoftwareKeyboardController.current
    var showTipsHistory by rememberSaveable { mutableStateOf(false) }
    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        CoachPageHeader()
        if (!aiAvailable) {
            CoachSurface(modifier = Modifier.testTag("coach_ai_unavailable")) {
                Text("Your journal works offline", style = MaterialTheme.typography.titleLarge)
                Text("AI coaching is not configured in this build. You can still record meals, review your history and manage saved plans.",
                    style = MaterialTheme.typography.bodyMedium)
                Text("Fruit lookup is available separately and requires internet.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        AgentPlanCard(
            state = coachUiState.agentState,
            aiAvailable = aiAvailable,
            planningNeed = coachUiState.planningNeed,
            planningNeedError = coachUiState.planningNeedError,
            onPlanningNeedChanged = onPlanningNeedChanged,
            onGenerate = { keyboardController?.hide(); onGeneratePlan() },
            onCancel = onCancelPlan,
            onRetry = onRetryPlan,
            onConfirm = onConfirmPlan,
            onAbandon = onAbandonPlan,
            onAbandonConfirmed = onAbandonConfirmedPlan
        )
        CoachSurface {
            CoachSectionTitle("Fruit nutrition", "A closer look at what is on your plate.")
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = fruitNutritionUiState.query,
                    onValueChange = onFruitQueryChanged,
                    label = { Text("Fruit Name") },
                    placeholder = { Text("Enter fruit name") },
                    modifier = Modifier.weight(1f).testTag("fruit_search_input"),
                    shape = RoundedCornerShape(16.dp),
                    singleLine = true
                )
                FilledTonalButton(
                    onClick = { keyboardController?.hide(); onSearchFruit() },
                    enabled = fruitNutritionUiState.status !is FruitSearchStatus.Loading && fruitNutritionUiState.query.isNotBlank(),
                    modifier = Modifier.heightIn(min = 56.dp).testTag("fruit_search_submit"),
                    shape = RoundedCornerShape(16.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp)
                ) {
                    if (fruitNutritionUiState.status is FruitSearchStatus.Loading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp).testTag("fruit_search_loading"),
                            color = MaterialTheme.colorScheme.primary,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(Icons.Default.Search, contentDescription = "Search", modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Details")
                    }
                }
            }
            when (val status = fruitNutritionUiState.status) {
                is FruitSearchStatus.Empty -> FruitSearchMessage(status.message, onRetryFruit)
                is FruitSearchStatus.Error -> FruitSearchMessage(status.message, onRetryFruit)
                else -> Unit
            }
            (fruitNutritionUiState.status as? FruitSearchStatus.Success)?.fruit?.let { fruit ->
                Column(
                    modifier = Modifier.fillMaxWidth().testTag("fruit_search_success"),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    FruitNutritionRow("family", fruit.family)
                    FruitNutritionRow("calories", fruit.calories.toInt().toString())
                    FruitNutritionRow("fat", fruit.fat.toString())
                    FruitNutritionRow("sugar", fruit.sugar.toString())
                    FruitNutritionRow("carbohydrates", fruit.carbohydrates.toInt().toString())
                    FruitNutritionRow("protein", fruit.protein.toInt().toString())
                }
            }
        }
        CoachSurface {
            CoachSectionTitle("A little encouragement", "Small changes build lasting habits.")
            Button(
                onClick = onGenerateMotivationalMessage,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("coach_generate_tip"),
                shape = RoundedCornerShape(16.dp),
                enabled = aiAvailable && coachUiState.motivationalState !is UiState.Loading
            ) {
                if (coachUiState.motivationalState is UiState.Loading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.width(10.dp))
                    Text("Generating...")
                } else {
                    Text("Motivational Message (AI)")
                }
            }
            when (val state = coachUiState.motivationalState) {
                is UiState.Initial -> Text(
                    if (aiAvailable) "Request a little encouragement based on your saved profile." else "AI messages become available when this build is configured for AI. Saved tips remain available below.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                is UiState.Loading -> Text(
                    "Generating your personalized tip...",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                is UiState.Success -> Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text(
                        state.outputText,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.fillMaxWidth().padding(18.dp)
                    )
                }
                is UiState.Error -> Text(
                    "Error: ${state.errorMessage}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }
            TextButton(onClick = { showTipsHistory = true }, modifier = Modifier.align(Alignment.End)) {
                Text("View saved tips")
            }
        }
        Spacer(Modifier.height(8.dp))
    }
    if (showTipsHistory) {
        AlertDialog(
            onDismissRequest = { showTipsHistory = false },
            title = { Text("AI Tips", style = MaterialTheme.typography.headlineSmall) },
            text = {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 360.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (coachUiState.previousTips.isEmpty()) {
                        item {
                            Text(
                                "No previous tips yet. Generate your first tip!",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        items(coachUiState.previousTips) { tip ->
                            Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(16.dp)) {
                                Text(
                                    tip.tipContent,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.fillMaxWidth().padding(16.dp)
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showTipsHistory = false }) { Text("Close") } }
        )
    }
}

@Composable
private fun CoachPageHeader() {
    val primary = MaterialTheme.colorScheme.primary
    val accent = MaterialTheme.colorScheme.secondaryContainer
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("NutriCoach", style = MaterialTheme.typography.headlineLarge)
            Text(
                "Good nourishment,\nguided by your habits.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Canvas(modifier = Modifier.size(76.dp)) {
            drawCircle(Brush.radialGradient(listOf(accent, accent.copy(alpha = 0.2f))))
            val leaf = Path().apply {
                moveTo(size.width * 0.34f, size.height * 0.76f)
                cubicTo(size.width * 0.18f, size.height * 0.35f, size.width * 0.4f, size.height * 0.18f, size.width * 0.76f, size.height * 0.22f)
                cubicTo(size.width * 0.8f, size.height * 0.58f, size.width * 0.52f, size.height * 0.76f, size.width * 0.34f, size.height * 0.76f)
                close()
            }
            drawPath(leaf, color = primary)
            drawLine(accent, Offset(size.width * 0.34f, size.height * 0.76f), Offset(size.width * 0.66f, size.height * 0.32f), strokeWidth = 2.dp.toPx())
        }
    }
}

@Composable
private fun CoachSurface(
    modifier: Modifier = Modifier,
    highlighted: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    NutriCard(modifier = modifier, highlighted = highlighted, content = content)
}

@Composable
private fun CoachSectionTitle(title: String, subtitle: String? = null) {
    NutriSectionTitle(title = title, subtitle = subtitle)
}

@Composable
private fun FruitNutritionRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
}

@Composable
internal fun AgentPlanCard(
    state: NutriCoachAgentUiState,
    planningNeed: String,
    planningNeedError: String?,
    onPlanningNeedChanged: (String) -> Unit,
    onGenerate: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onConfirm: () -> Unit = {},
    onAbandon: () -> Unit = {},
    onAbandonConfirmed: () -> Unit = {},
    aiAvailable: Boolean = true
) {
    CoachSurface(modifier = Modifier.testTag("agent_plan_card"), highlighted = true) {
        CoachSectionTitle("Nutrition Plan Agent", "Your saved habits. A more considered plan.")
        OutlinedTextField(
            value = planningNeed,
            onValueChange = onPlanningNeedChanged,
            enabled = aiAvailable && state !is NutriCoachAgentUiState.Loading && state !is NutriCoachAgentUiState.LoggedOut,
            isError = planningNeedError != null,
            label = { Text("What should the plan help with?") },
            placeholder = { Text("Example: Use my recent intake to improve hydration next week") },
            supportingText = {
                Text(planningNeedError ?: "Do not include names, contact details, account IDs, or credentials.")
            },
            modifier = Modifier.fillMaxWidth().testTag("agent_request_input")
                .semantics { contentDescription = "Nutrition planning request" },
            shape = RoundedCornerShape(16.dp),
            minLines = 2,
            maxLines = 4
        )
        when (state) {
            NutriCoachAgentUiState.Idle -> {
                Text(
                    if (aiAvailable) "Request a draft using your saved profile and intake history. Your request and selected evidence are sent to the AI service." else "Plan generation needs AI configuration. Your saved plans and local records remain available.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                AgentActionButton(
                    text = "Generate Nutrition Plan Draft", onClick = onGenerate,
                    enabled = aiAvailable && planningNeed.isNotBlank(), tag = "agent_generate_plan",
                    description = "Generate nutrition plan draft"
                )
            }
            is NutriCoachAgentUiState.Loading -> {
                Row(
                    modifier = Modifier.fillMaxWidth().testTag("agent_loading")
                        .semantics { contentDescription = "Nutrition plan generation in progress" },
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    Text(
                        if (state.canCancel) "Generating your draft..." else "Loading your latest draft...",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                if (state.canCancel) AgentActionButton("Cancel", onCancel, true, "agent_cancel", "Cancel nutrition plan generation", outlined = true)
            }
            is NutriCoachAgentUiState.DraftReady -> {
                AgentDraftContent(state)
                Text(
                    "This draft has no effect until you confirm adoption.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                state.actionError?.let { AgentActionError(it, "agent_plan_action_error") }
                AgentActionButton(
                    if (state.isActionInProgress) "Updating Draft..." else "Confirm Adoption",
                    onConfirm, !state.isActionInProgress, "agent_confirm_plan", "Confirm nutrition plan adoption"
                )
                AgentActionButton(
                    "Discard Draft", onAbandon, !state.isActionInProgress,
                    "agent_abandon_plan", "Discard nutrition plan draft", outlined = true
                )
                AgentActionButton(
                    "Generate New Draft", onGenerate, aiAvailable && planningNeed.isNotBlank() && !state.isActionInProgress,
                    "agent_generate_plan", "Generate a new nutrition plan draft", outlined = true
                )
            }
            is NutriCoachAgentUiState.Confirmed -> {
                AgentStoredPlanContent(
                    state.draft, state.audit,
                    if (state.isActive) "ACTIVE · COMPLETION SCHEDULED" else "CONFIRMED · COMPLETION SCHEDULED",
                    "Confirmed at ${state.confirmedAt}. Completion is checked locally after the plan end date; no reminder, network request, or health record is created.",
                    "agent_plan_confirmed"
                )
                state.lifecycleError?.let { AgentActionError(it, "agent_plan_lifecycle_error") }
                AgentActionButton(
                    if (state.isActionInProgress) "Stopping Plan..." else "Stop Plan",
                    onAbandonConfirmed, !state.isActionInProgress,
                    "agent_abandon_confirmed_plan", "Stop confirmed nutrition plan", outlined = true
                )
                AgentActionButton("Generate New Draft", onGenerate, aiAvailable && planningNeed.isNotBlank(), "agent_generate_plan", outlined = true)
            }
            is NutriCoachAgentUiState.Completed -> {
                AgentStoredPlanContent(
                    state.draft, state.audit, "COMPLETED · NOT ACTIVE",
                    "Completed at ${state.completedAt}. The completed plan remains available in local audit history.", "agent_plan_completed"
                )
                AgentActionButton("Generate New Draft", onGenerate, aiAvailable && planningNeed.isNotBlank(), "agent_generate_plan", outlined = true)
            }
            is NutriCoachAgentUiState.Abandoned -> {
                AgentStoredPlanContent(
                    state.draft, state.audit, "PLAN DISCARDED · NOT ACTIVE",
                    "This discarded plan remains in the audit history and cannot be confirmed or resumed.", "agent_plan_abandoned"
                )
                AgentActionButton("Generate New Draft", onGenerate, aiAvailable && planningNeed.isNotBlank(), "agent_generate_plan", outlined = true)
            }
            is NutriCoachAgentUiState.Error -> {
                Column(modifier = Modifier.testTag("agent_error"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Plan generation unavailable", style = MaterialTheme.typography.titleMedium)
                    Text(state.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    AgentAuditSummary(state.audit)
                    if (state.canRetry) AgentActionButton(
                        "Retry", onRetry, !state.retryRequiresPlanningNeed || (aiAvailable && planningNeed.isNotBlank()),
                        "agent_retry", "Retry nutrition plan operation"
                    )
                }
            }
            is NutriCoachAgentUiState.Cancelled -> {
                Column(modifier = Modifier.testTag("agent_cancelled"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Generation cancelled", style = MaterialTheme.typography.titleMedium)
                    Text("No plan was activated. You can start a new draft when ready.", style = MaterialTheme.typography.bodyMedium)
                    AgentAuditSummary(state.audit)
                    AgentActionButton("Generate Again", onRetry, aiAvailable && planningNeed.isNotBlank(), "agent_retry", "Generate nutrition plan again")
                }
            }
            NutriCoachAgentUiState.LoggedOut -> Text(
                "Sign in to generate a nutrition plan draft.", style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag("agent_logged_out")
            )
        }
    }
}

@Composable
private fun AgentActionButton(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean,
    tag: String,
    description: String? = null,
    outlined: Boolean = false
) {
    val modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag(tag).let { base ->
        if (description != null) base.semantics { contentDescription = description } else base
    }
    if (outlined) {
        OutlinedButton(onClick = onClick, enabled = enabled, modifier = modifier, shape = RoundedCornerShape(16.dp)) {
            Text(text, style = MaterialTheme.typography.labelLarge)
        }
    } else {
        Button(onClick = onClick, enabled = enabled, modifier = modifier, shape = RoundedCornerShape(16.dp)) {
            Text(text, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun AgentActionError(message: String, tag: String) {
    Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag(tag))
}

@Composable
private fun AgentDraftContent(state: NutriCoachAgentUiState.DraftReady) {
    AgentStoredPlanContent(state.draft, state.audit, "DRAFT · NOT ACTIVE", null, "agent_draft")
}

@Composable
private fun AgentStoredPlanContent(
    draft: AgentDraftPlanUiModel,
    audit: List<AgentAuditUiModel>,
    status: String,
    description: String?,
    tag: String
) {
    Column(modifier = Modifier.testTag(tag), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Text(status, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        description?.let {
            Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
        Text(draft.summary, modifier = Modifier.testTag("agent_summary"), style = MaterialTheme.typography.titleLarge)
        Text(
            "Valid ${draft.validFrom} to ${draft.validTo}", modifier = Modifier.testTag("agent_validity"),
            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall
        )
        CoachSectionTitle("Recommendations")
        draft.recommendations.forEachIndexed { index, recommendation ->
            Surface(
                modifier = Modifier.fillMaxWidth().testTag("agent_recommendation_$index"),
                color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(18.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(recommendation.title, style = MaterialTheme.typography.titleMedium)
                    Text(recommendation.action, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Target: ${recommendation.target}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
        Column(modifier = Modifier.testTag("agent_cautions"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Cautions", style = MaterialTheme.typography.titleMedium)
            if (draft.cautions.isEmpty()) {
                Text("No specific cautions were identified.", style = MaterialTheme.typography.bodyMedium)
            } else {
                draft.cautions.forEach { caution -> Text("• $caution", style = MaterialTheme.typography.bodyMedium) }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Column(modifier = Modifier.testTag("agent_disclaimer"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Disclaimer", style = MaterialTheme.typography.titleSmall)
            Text(draft.disclaimer, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
        AgentAuditSummary(audit)
    }
}

@Composable
private fun AgentAuditSummary(audit: List<AgentAuditUiModel>) {
    if (audit.isEmpty()) return
    Column(modifier = Modifier.testTag("agent_audit"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Evidence audit", style = MaterialTheme.typography.titleMedium)
        audit.forEach { entry ->
            Row(
                modifier = Modifier.fillMaxWidth().testTag("agent_audit_${entry.sequence}"),
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top
            ) {
                Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(10.dp)) {
                    Text(
                        entry.sequence.toString(), modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary
                    )
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(entry.toolName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    Text(
                        "${entry.status} · ${entry.time}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun FruitSearchMessage(message: String, onRetry: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().testTag("fruit_search_message"),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically
    ) {
        Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        TextButton(onClick = onRetry, modifier = Modifier.testTag("fruit_search_retry")) { Text("Retry") }
    }
}
