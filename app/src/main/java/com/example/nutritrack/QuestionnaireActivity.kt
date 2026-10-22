package com.example.nutritrack

import android.annotation.SuppressLint
import android.app.TimePickerDialog
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.example.nutritrack.data.QuestionnaireAnswers
import com.example.nutritrack.data.QuestionnaireProfileRules
import com.example.nutritrack.data.QuestionnaireUiState
import com.example.nutritrack.data.QuestionnaireViewModel
import com.example.nutritrack.ui.theme.NutritrackTheme
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.util.*

class QuestionnaireActivity : ComponentActivity() {
    private val questionnaireViewModel: QuestionnaireViewModel by viewModels {
        QuestionnaireViewModel.Factory(
            dataSource = (applicationContext as NutrackApplication).repository,
            userId = intent.getStringExtra("USER_ID")?.toIntOrNull() ?: 0
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val userId = intent.getStringExtra("USER_ID")?.toIntOrNull() ?: 0
        val phoneNumber = intent.getStringExtra("PHONE_NUMBER") ?: ""

        enableEdgeToEdge()
        setContent {
            NutritrackTheme {
                val uiState by questionnaireViewModel.uiState.collectAsStateWithLifecycle()
                LaunchedEffect(uiState.savedEventId) {
                    val eventId = uiState.savedEventId ?: return@LaunchedEffect
                    val destination = Intent(this@QuestionnaireActivity, HomeActivity::class.java)
                    destination.putExtra("USER_ID", userId.toString())
                    destination.putExtra("PHONE_NUMBER", phoneNumber)
                    startActivity(destination)
                    questionnaireViewModel.savedEventHandled(eventId)
                    finish()
                }
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    QuestionnaireScreen(
                        uiState = uiState,
                        onFoodSelectionChanged = questionnaireViewModel::setFoodSelection,
                        onPersonaSelected = questionnaireViewModel::selectPersona,
                        onBiggestMealTimeChanged = questionnaireViewModel::setBiggestMealTime,
                        onSleepTimeChanged = questionnaireViewModel::setSleepTime,
                        onWakeUpTimeChanged = questionnaireViewModel::setWakeUpTime,
                        onSave = questionnaireViewModel::save,
                        onRetry = questionnaireViewModel::retry,
                        onBackPressed = { finish() }
                    )
                }
            }
        }
    }
}

@SuppressLint("DefaultLocale")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuestionnaireScreen(
    uiState: QuestionnaireUiState,
    onFoodSelectionChanged: (String, Boolean) -> Unit,
    onPersonaSelected: (String) -> Unit,
    onBiggestMealTimeChanged: (String) -> Unit,
    onSleepTimeChanged: (String) -> Unit,
    onWakeUpTimeChanged: (String) -> Unit,
    onSave: () -> Unit,
    onRetry: () -> Unit,
    onBackPressed: () -> Unit
) {
    val context = LocalContext.current
    val answers = uiState.answers

    // Food categories state
    val foodCategories = QuestionnaireProfileRules.foodCategories

    // Persona state
    val personas = QuestionnaireProfileRules.personas
    var showPersonaDialog by remember { mutableStateOf(false) }
    var selectedPersonaForDialog by remember { mutableStateOf("") }

    // Persona descriptions
    val personaDescriptions = mapOf(
        "Health Devotee" to "I'm passionate about healthy eating & health plays a big part in my life. I use social media to follow active lifestyle personalities or get new recipes/exercise ideas. I may even buy superfoods or follow a particular type of diet. I like to think I am super healthy.",
        "Mindful Eater" to "I'm health-conscious and being healthy and eating healthy is important to me. Although health means different things to different people, I make conscious lifestyle decisions about eating based on what I believe healthy means. I look for new recipes and healthy eating information on social media.",
        "Wellness Striver" to "I aspire to be healthy (but struggle sometimes). Healthy eating is hard work! I've tried to improve my diet, but always find things that make it difficult to stick with the changes. Sometimes I notice recipe ideas or healthy eating hacks, and if it seems easy enough, I'll give it a go.",
        "Balance Seeker" to "I try and live a balanced lifestyle, and I think that all foods are okay in moderation. I shouldn't have to feel guilty about eating a piece of cake now and again. I get all sorts of inspiration from social media like finding out about new restaurants, fun recipes and sometimes healthy eating tips.",
        "Health Procrastinator" to "I'm contemplating healthy eating but it's not a priority for me right now. I know the basics about what it means to be healthy, but it doesn't seem relevant to me right now. I have taken a few steps to be healthier but I am not motivated to make it a high priority because I have too many other things going on in my life.",
        "Food Carefree" to "I'm not bothered about healthy eating. I don't really see the point and I don't think about it. I don't really notice healthy eating tips or recipes and I don't care what I eat."
    )

    // Time selection state
    var showTimePicker by remember { mutableStateOf(false) }
    var currentTimeSelection by remember { mutableStateOf<TimeSelection>(TimeSelection.NONE) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Food Intake Questionnaire",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.fillMaxWidth()
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBackPressed) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 20.dp)
        ) {
            Text(
                text = "Make nutrition personal.",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "Your preferences and daily rhythm help shape your guidance.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp, bottom = 24.dp)
            )
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = RoundedCornerShape(24.dp)
            ) {
                Column(Modifier.padding(16.dp)) {
                    // Food categories selection
                    Text(
                        text = "Tick all the food categories you can eat",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(vertical = 12.dp)
                    )

                    foodCategories.chunked(2).forEach { rowCategories ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            rowCategories.forEach { category ->
                                Row(
                                    modifier = Modifier
                                        .weight(1f),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Start
                                ) {
                                    Checkbox(
                                        checked = answers.isCategorySelected(category),
                                        onCheckedChange = { checked ->
                                            onFoodSelectionChanged(category, checked)
                                        },
                                        modifier = Modifier
                                            .size(48.dp)
                                            .testTag("questionnaire_food_$category")
                                    )
                                    Text(
                                        text = category,
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = RoundedCornerShape(24.dp)
            ) {
                Column(Modifier.padding(20.dp)) {
                    Text(
                        text = "Your Persona",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    Text(
                        text = "People can be broadly classified into 6 different types based on their eating preferences. Click on each button below to find out the different types, and select the type that best fits you!",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )
                    personas.chunked(2).forEach { rowPersonas ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            rowPersonas.forEach { persona ->
                                Button(
                                    onClick = {
                                        selectedPersonaForDialog = persona
                                        showPersonaDialog = true
                                    },
                                    modifier = Modifier.weight(1f).heightIn(min = 56.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                                    ),
                                    shape = RoundedCornerShape(14.dp),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 12.dp)
                                ) {
                                    Text(
                                        text = persona,
                                        style = MaterialTheme.typography.labelLarge,
                                        textAlign = TextAlign.Center,
                                        maxLines = 2,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = "Which persona best fits you?",
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    var expanded by remember { mutableStateOf(false) }
                    ExposedDropdownMenuBox(
                        expanded = expanded,
                        onExpandedChange = { expanded = !expanded },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OutlinedTextField(
                            value = answers.selectedPersona.ifEmpty { "Select option" },
                            onValueChange = {},
                            readOnly = true,
                            trailingIcon = {
                                ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor()
                                .testTag("questionnaire_persona"),
                            shape = RoundedCornerShape(14.dp)
                        )
                        ExposedDropdownMenu(
                            expanded = expanded,
                            onDismissRequest = { expanded = false },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            personas.forEach { persona ->
                                DropdownMenuItem(
                                    text = { Text(persona) },
                                    onClick = {
                                        onPersonaSelected(persona)
                                        expanded = false
                                    },
                                    modifier = Modifier
                                        .heightIn(min = 48.dp)
                                        .testTag("questionnaire_persona_option_$persona")
                                )
                            }
                        }
                    }
                }
            }

            // Persona details dialog
            if (showPersonaDialog) {
                BasicAlertDialog(onDismissRequest = { showPersonaDialog = false },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    properties = DialogProperties(
                        dismissOnBackPress = true,
                        dismissOnClickOutside = true
                    ),
                    content = {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .wrapContentHeight(),
                            shape = RoundedCornerShape(24.dp),
                            color = MaterialTheme.colorScheme.surface
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 600.dp)
                                    .verticalScroll(rememberScrollState())
                                    .padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                val imageResId = when(selectedPersonaForDialog) {
                                    "Health Devotee" -> R.drawable.persona_1
                                    "Mindful Eater" -> R.drawable.persona_2
                                    "Wellness Striver" -> R.drawable.persona_3
                                    "Balance Seeker" -> R.drawable.persona_4
                                    "Health Procrastinator" -> R.drawable.persona_5
                                    "Food Carefree" -> R.drawable.persona_6
                                    else -> R.drawable.persona_1
                                }
                                Image(
                                    painter = painterResource(id = imageResId),
                                    contentDescription = "Persona Image",
                                    modifier = Modifier
                                        .size(120.dp)
                                        .padding(bottom = 12.dp)
                                )

                                Text(
                                    text = selectedPersonaForDialog,
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.Bold,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(bottom = 12.dp)
                                )

                                Text(
                                    text = personaDescriptions[selectedPersonaForDialog] ?: "",
                                    fontSize = 14.sp,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(bottom = 24.dp)
                                )

                                Button(
                                    onClick = { showPersonaDialog = false },
                                    modifier = Modifier
                                        .fillMaxWidth(0.5f)
                                        .heightIn(min = 56.dp),
                                    shape = RoundedCornerShape(16.dp)
                                ) {
                                    Text("Dismiss")
                                }
                            }
                        }
                    }
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = RoundedCornerShape(24.dp)
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    Text(
                        text = "Timings",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    QuestionnaireTimeField(
                        label = "What time of day approx. do you\nnormally eat your biggest meal?",
                        time = answers.biggestMealTime,
                        onClick = {
                            currentTimeSelection = TimeSelection.BIGGEST_MEAL
                            showTimePicker = true
                        }
                    )
                    QuestionnaireTimeField(
                        label = "What time of day approx. do you\ngo to sleep at night?",
                        time = answers.sleepTime,
                        onClick = {
                            currentTimeSelection = TimeSelection.SLEEP
                            showTimePicker = true
                        }
                    )
                    QuestionnaireTimeField(
                        label = "What time of day approx. do you\nwake up in the morning?",
                        time = answers.wakeUpTime,
                        onClick = {
                            currentTimeSelection = TimeSelection.WAKE_UP
                            showTimePicker = true
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Error message display
            if (uiState.errorMessage != null) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = uiState.errorMessage,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            fontSize = 14.sp,
                            modifier = Modifier.padding(12.dp),
                            textAlign = TextAlign.Center
                        )
                        if (!uiState.isLoading && !uiState.isSaving) {
                            TextButton(onClick = onRetry) { Text("Retry loading") }
                        }
                    }
                }
            }

            // Save button
            Button(
                onClick = onSave,
                enabled = !uiState.isLoading && !uiState.isSaving,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .testTag("questionnaire_save")
                    .align(Alignment.CenterHorizontally),
                shape = RoundedCornerShape(16.dp)
            ) {
                Row(
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.save),
                        contentDescription = "Save",
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Save", fontSize = 18.sp)
                }
            }
        }

        // Time picker dialog - Using native Android TimePickerDialog
        if (showTimePicker) {
            val calendar = Calendar.getInstance()
            val hour = calendar.get(Calendar.HOUR_OF_DAY)
            val minute = calendar.get(Calendar.MINUTE)
            
            val timePickerDialog = TimePickerDialog(
                context,
                { _, selectedHour, selectedMinute ->
                    val time = String.format("%02d:%02d", selectedHour, selectedMinute)
                    when (currentTimeSelection) {
                        TimeSelection.BIGGEST_MEAL -> onBiggestMealTimeChanged(time)
                        TimeSelection.SLEEP -> onSleepTimeChanged(time)
                        TimeSelection.WAKE_UP -> onWakeUpTimeChanged(time)
                        TimeSelection.NONE -> {}
                    }
                    showTimePicker = false
                },
                hour,
                minute,
                true // 24-hour format
            )
            
            timePickerDialog.setOnDismissListener {
                showTimePicker = false
            }
            
            LaunchedEffect(showTimePicker) {
                timePickerDialog.show()
            }
        }
    }
}

@Composable
private fun QuestionnaireTimeField(label: String, time: String, onClick: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            shape = RoundedCornerShape(14.dp)
        ) {
            Icon(
                painter = painterResource(R.drawable.clock),
                contentDescription = "Clock",
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(time, style = MaterialTheme.typography.titleMedium)
        }
    }
}

enum class TimeSelection {
    NONE,
    BIGGEST_MEAL,
    SLEEP,
    WAKE_UP
}

private fun QuestionnaireAnswers.isCategorySelected(category: String): Boolean = when (category) {
    "Fruits" -> canEatFruits
    "Vegetables" -> canEatVegetables
    "Grains" -> canEatGrains
    "Red Meat" -> canEatRedMeat
    "Seafood" -> canEatSeafood
    "Poultry" -> canEatPoultry
    "Fish" -> canEatFish
    "Eggs" -> canEatEggs
    "Nuts/Seeds" -> canEatNutsAndSeeds
    else -> false
}
