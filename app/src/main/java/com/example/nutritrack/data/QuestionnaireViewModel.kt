package com.example.nutritrack.data

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class QuestionnaireUiState(
    val answers: QuestionnaireAnswers,
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val isComplete: Boolean = false,
    val errorMessage: String? = null,
    val savedEventId: Long? = null
)

interface QuestionnaireDataSource {
    suspend fun migrateLegacyQuestionnaire(userId: Int): LegacyQuestionnaireMigrationResult
    suspend fun getQuestionnaireProfile(userId: Int): QuestionnaireProfile?
    suspend fun saveQuestionnaireProfile(answers: QuestionnaireAnswers): QuestionnaireProfile
}

class QuestionnaireViewModel(
    private val dataSource: QuestionnaireDataSource,
    private val userId: Int
) : ViewModel() {
    private val _uiState = MutableStateFlow(
        QuestionnaireUiState(answers = emptyAnswers(userId))
    )
    val uiState: StateFlow<QuestionnaireUiState> = _uiState.asStateFlow()

    private var nextEventId = 1L

    init {
        load()
    }

    fun retry() {
        if (_uiState.value.isLoading || _uiState.value.isSaving) return
        load()
    }

    fun setFoodSelection(category: String, selected: Boolean) {
        if (category !in QuestionnaireProfileRules.foodCategories) return
        updateAnswers { answers ->
            when (category) {
                "Fruits" -> answers.copy(canEatFruits = selected)
                "Vegetables" -> answers.copy(canEatVegetables = selected)
                "Grains" -> answers.copy(canEatGrains = selected)
                "Red Meat" -> answers.copy(canEatRedMeat = selected)
                "Seafood" -> answers.copy(canEatSeafood = selected)
                "Poultry" -> answers.copy(canEatPoultry = selected)
                "Fish" -> answers.copy(canEatFish = selected)
                "Eggs" -> answers.copy(canEatEggs = selected)
                "Nuts/Seeds" -> answers.copy(canEatNutsAndSeeds = selected)
                else -> answers
            }
        }
    }

    fun selectPersona(persona: String) {
        updateAnswers { it.copy(selectedPersona = persona) }
    }

    fun setBiggestMealTime(value: String) {
        updateAnswers { it.copy(biggestMealTime = value) }
    }

    fun setSleepTime(value: String) {
        updateAnswers { it.copy(sleepTime = value) }
    }

    fun setWakeUpTime(value: String) {
        updateAnswers { it.copy(wakeUpTime = value) }
    }

    fun save() {
        if (_uiState.value.isSaving || _uiState.value.isLoading) return
        val answers = _uiState.value.answers.copy(userId = userId)
        val validationError = QuestionnaireProfileRules.validate(answers)
        if (validationError != null) {
            _uiState.update { it.copy(errorMessage = validationError.toUserMessage()) }
            return
        }

        _uiState.update { it.copy(isSaving = true, errorMessage = null) }
        viewModelScope.launch {
            try {
                val profile = dataSource.saveQuestionnaireProfile(answers)
                check(profile.userId == userId && QuestionnaireProfileRules.isValid(profile)) {
                    "Saved questionnaire does not belong to the current user"
                }
                _uiState.update {
                    it.copy(
                        answers = profile.toAnswers(),
                        isSaving = false,
                        isComplete = true,
                        savedEventId = nextEventId++
                    )
                }
            } catch (exception: Exception) {
                _uiState.update {
                    it.copy(
                        isSaving = false,
                        errorMessage = exception.message?.takeIf(String::isNotBlank)
                            ?: "Unable to save questionnaire data. Please try again."
                    )
                }
            }
        }
    }

    fun savedEventHandled(eventId: Long) {
        _uiState.update { state ->
            if (state.savedEventId == eventId) state.copy(savedEventId = null) else state
        }
    }

    private fun load() {
        if (userId <= 0) {
            _uiState.update {
                it.copy(isLoading = false, errorMessage = "Unable to identify the current user")
            }
            return
        }
        _uiState.update { it.copy(isLoading = true, errorMessage = null) }
        viewModelScope.launch {
            try {
                dataSource.migrateLegacyQuestionnaire(userId)
                val profile = dataSource.getQuestionnaireProfile(userId)
                    ?.takeIf { it.userId == userId && QuestionnaireProfileRules.isValid(it) }
                _uiState.update {
                    it.copy(
                        answers = profile?.toAnswers() ?: emptyAnswers(userId),
                        isLoading = false,
                        isComplete = profile != null
                    )
                }
            } catch (exception: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = exception.message?.takeIf(String::isNotBlank)
                            ?: "Unable to load questionnaire data. Please try again."
                    )
                }
            }
        }
    }

    private fun updateAnswers(transform: (QuestionnaireAnswers) -> QuestionnaireAnswers) {
        if (_uiState.value.isSaving) return
        _uiState.update { state ->
            state.copy(
                answers = transform(state.answers).copy(userId = userId),
                errorMessage = null
            )
        }
    }

    class Factory(
        private val dataSource: QuestionnaireDataSource,
        private val userId: Int
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(QuestionnaireViewModel::class.java))
            return QuestionnaireViewModel(dataSource, userId) as T
        }
    }

    private companion object {
        fun emptyAnswers(userId: Int) = QuestionnaireAnswers(
            userId = userId,
            canEatFruits = false,
            canEatVegetables = false,
            canEatGrains = false,
            canEatRedMeat = false,
            canEatSeafood = false,
            canEatPoultry = false,
            canEatFish = false,
            canEatEggs = false,
            canEatNutsAndSeeds = false,
            selectedPersona = "",
            biggestMealTime = "00:00",
            sleepTime = "00:00",
            wakeUpTime = "00:00"
        )
    }
}

internal fun QuestionnaireValidationError.toUserMessage(): String = when (this) {
    QuestionnaireValidationError.INVALID_USER -> "Unable to identify the current user"
    QuestionnaireValidationError.NO_FOOD_SELECTED -> "Please select at least one food category"
    QuestionnaireValidationError.INVALID_PERSONA -> "Please select a persona"
    QuestionnaireValidationError.INVALID_TIME -> "Please select valid meal, sleep, and wake times"
    QuestionnaireValidationError.TIME_CONFLICT ->
        "Time conflict: meal, sleep, and wake times cannot be within 30 minutes of each other"
}
