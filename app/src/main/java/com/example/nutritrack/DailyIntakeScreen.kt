package com.example.nutritrack

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.nutritrack.data.DailyIntakeFormInput
import com.example.nutritrack.data.DailyIntakeUiState
import com.example.nutritrack.data.FoodIntake
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DailyIntakeScreen(
    uiState: DailyIntakeUiState,
    onBack: () -> Unit,
    onSelectDate: (LocalDate) -> Unit,
    onAdd: () -> Unit,
    onEdit: (Long) -> Unit,
    onSave: (DailyIntakeFormInput) -> Unit,
    onDismissEditor: () -> Unit,
    onClearFeedback: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Daily intake", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAdd,
                modifier = Modifier.testTag("daily_intake_add"),
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shape = RoundedCornerShape(18.dp),
                icon = { Icon(Icons.Default.Add, contentDescription = "Add intake") },
                text = { Text("Add intake", fontWeight = FontWeight.SemiBold) }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                "Every meal is a fresh start.",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                "Keep a simple record of what nourishes you.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = RoundedCornerShape(20.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = { onSelectDate(uiState.selectedDate.minusDays(1)) }) {
                        Text("Previous")
                    }
                    Text(
                        text = uiState.selectedDate.toString(),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    TextButton(onClick = { onSelectDate(uiState.selectedDate.plusDays(1)) }) {
                        Text("Next")
                    }
                }
            }

            uiState.statusMessage?.let { message ->
                FeedbackCard(message = message, color = MaterialTheme.colorScheme.primary, onDismiss = onClearFeedback)
            }
            uiState.errorMessage?.let { message ->
                FeedbackCard(message = message, color = MaterialTheme.colorScheme.error, onDismiss = onClearFeedback)
            }

            when {
                uiState.isLoading -> Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
                uiState.records.isEmpty() -> Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    shape = RoundedCornerShape(24.dp)
                ) {
                    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("No intake records for this date.", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Add a meal or drink to start your daily record.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                else -> uiState.records.forEach { record ->
                    DailyIntakeCard(record = record, onEdit = { onEdit(record.id) })
                }
            }
            Spacer(modifier = Modifier.height(88.dp))
        }
    }

    if (uiState.isEditorOpen) {
        DailyIntakeEditorDialog(
            selectedDate = uiState.selectedDate,
            record = uiState.editingRecord,
            isSaving = uiState.isSaving,
            errorMessage = uiState.errorMessage,
            onDismiss = onDismissEditor,
            onSave = onSave
        )
    }
}

@Composable
private fun FeedbackCard(message: String, color: Color, onDismiss: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onDismiss),
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.1f)),
        shape = RoundedCornerShape(16.dp)
    ) {
        Text(message, color = color, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(16.dp))
    }
}

@Composable
private fun DailyIntakeCard(record: FoodIntake, onEdit: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(record.foodName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                IconButton(onClick = onEdit) {
                    Icon(Icons.Default.Edit, contentDescription = "Edit ${record.foodName}")
                }
            }
            Text(
                "Vegetables ${record.vegetablesServings} · Fruit ${record.fruitServings} · " +
                    "Grains ${record.grainsServings}",
                fontSize = 14.sp
            )
            Text(
                "Meat ${record.meatServings} · Dairy ${record.dairyServings} · " +
                    "Water ${record.waterMl} mL",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun DailyIntakeEditorDialog(
    selectedDate: LocalDate,
    record: FoodIntake?,
    isSaving: Boolean,
    errorMessage: String?,
    onDismiss: () -> Unit,
    onSave: (DailyIntakeFormInput) -> Unit
) {
    var foodName by remember(record?.id) { mutableStateOf(record?.foodName.orEmpty()) }
    var vegetables by remember(record?.id) { mutableStateOf(record.formattedValue(FoodIntake::vegetablesServings)) }
    var fruit by remember(record?.id) { mutableStateOf(record.formattedValue(FoodIntake::fruitServings)) }
    var grains by remember(record?.id) { mutableStateOf(record.formattedValue(FoodIntake::grainsServings)) }
    var meat by remember(record?.id) { mutableStateOf(record.formattedValue(FoodIntake::meatServings)) }
    var dairy by remember(record?.id) { mutableStateOf(record.formattedValue(FoodIntake::dairyServings)) }
    var discretionary by remember(record?.id) { mutableStateOf(record.formattedValue(FoodIntake::discretionaryServings)) }
    var sodium by remember(record?.id) { mutableStateOf(record.formattedValue(FoodIntake::sodiumMg)) }
    var alcohol by remember(record?.id) { mutableStateOf(record.formattedValue(FoodIntake::alcoholStandardDrinks)) }
    var water by remember(record?.id) { mutableStateOf(record.formattedValue(FoodIntake::waterMl)) }
    var totalBeverage by remember(record?.id) { mutableStateOf(record.formattedValue(FoodIntake::totalBeverageMl)) }
    var sugar by remember(record?.id) { mutableStateOf(record.formattedValue(FoodIntake::sugarG)) }
    var saturatedFat by remember(record?.id) { mutableStateOf(record.formattedValue(FoodIntake::saturatedFatG)) }
    var unsaturatedFat by remember(record?.id) { mutableStateOf(record.formattedValue(FoodIntake::unsaturatedFatG)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(28.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text(if (record == null) "Add daily intake" else "Edit daily intake") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 560.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("Date: $selectedDate", fontWeight = FontWeight.Medium)
                errorMessage?.let { message ->
                    Text(message, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                }
                OutlinedTextField(
                    value = foodName,
                    onValueChange = { foodName = it },
                    label = { Text("Food name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp)
                )
                NumericField("Vegetable servings", vegetables) { vegetables = it }
                NumericField("Fruit servings", fruit) { fruit = it }
                NumericField("Grain servings", grains) { grains = it }
                NumericField("Meat servings", meat) { meat = it }
                NumericField("Dairy servings", dairy) { dairy = it }
                NumericField("Discretionary servings", discretionary) { discretionary = it }
                NumericField("Sodium (mg)", sodium) { sodium = it }
                NumericField("Alcohol (standard drinks)", alcohol) { alcohol = it }
                NumericField("Water (mL)", water) { water = it }
                NumericField("Total beverage (mL)", totalBeverage) { totalBeverage = it }
                NumericField("Sugar (g)", sugar) { sugar = it }
                NumericField("Saturated fat (g)", saturatedFat) { saturatedFat = it }
                NumericField("Unsaturated fat (g)", unsaturatedFat) { unsaturatedFat = it }
            }
        },
        confirmButton = {
            Button(
                enabled = !isSaving,
                modifier = Modifier.heightIn(min = 56.dp).testTag("daily_intake_save"),
                shape = RoundedCornerShape(16.dp),
                onClick = {
                    onSave(
                        DailyIntakeFormInput(
                            recordId = record?.id,
                            foodName = foodName,
                            localDate = selectedDate,
                            vegetablesServings = vegetables,
                            fruitServings = fruit,
                            grainsServings = grains,
                            meatServings = meat,
                            dairyServings = dairy,
                            discretionaryServings = discretionary,
                            sodiumMg = sodium,
                            alcoholStandardDrinks = alcohol,
                            waterMl = water,
                            totalBeverageMl = totalBeverage,
                            sugarG = sugar,
                            saturatedFatG = saturatedFat,
                            unsaturatedFatG = unsaturatedFat
                        )
                    )
                }
            ) {
                Text(if (isSaving) "Saving…" else "Save")
            }
        },
        dismissButton = {
            TextButton(enabled = !isSaving, onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun NumericField(label: String, value: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp)
    )
}

private fun FoodIntake?.formattedValue(selector: (FoodIntake) -> Double): String =
    this?.let(selector)?.let { value ->
        if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()
    }.orEmpty()
