package com.example.nutritrack.data

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CategoryScore(
    val name: String,
    val score: Float,
    val maxScore: Float = 10f
)

data class DashboardUiState(
    val userId: Int,
    val selectedRoute: AppRoute,
    val patient: Patient? = null,
    val questionnaireProfile: QuestionnaireProfile? = null,
    val foodQualityScore: Float = 0f,
    val categoryScores: List<CategoryScore> = emptyList(),
    val dailyIntakeSummaries: List<DailyIntakeSummary> = emptyList(),
    val isLoading: Boolean = true,
    val isHistoryLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val isLoggingOut: Boolean = false,
    val errorMessage: String? = null,
    val intakeErrorMessage: String? = null
)

interface DashboardDataSource {
    suspend fun getPatientByUserId(userId: Int): Patient?
    suspend fun migrateLegacyQuestionnaire(userId: Int): LegacyQuestionnaireMigrationResult
    suspend fun getQuestionnaireProfile(userId: Int): QuestionnaireProfile?
}

interface DashboardSessionDataSource {
    suspend fun saveLastPage(route: AppRoute)
    suspend fun clearSession()
}

class DashboardViewModel(
    private val dataSource: DashboardDataSource,
    private val sessionDataSource: DashboardSessionDataSource,
    private val userId: Int,
    initialRoute: AppRoute,
    private val dailyIntakeState: StateFlow<DailyIntakeUiState>
) : ViewModel() {
    private val _uiState = MutableStateFlow(
        DashboardUiState(userId = userId, selectedRoute = initialRoute)
    )
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    private var refreshJob: Job? = null
    private var routeSaveJob: Job? = null

    init {
        observeDailyIntake()
        refresh()
    }

    fun refresh() {
        if (refreshJob?.isActive == true) return
        if (userId <= 0) {
            _uiState.update {
                it.copy(
                    isLoading = false,
                    isRefreshing = false,
                    errorMessage = "No signed-in user is available"
                )
            }
            return
        }
        _uiState.update { it.copy(isLoading = true, isRefreshing = true, errorMessage = null) }
        refreshJob = viewModelScope.launch {
            try {
                val patient = dataSource.getPatientByUserId(userId)
                dataSource.migrateLegacyQuestionnaire(userId)
                val profile = dataSource.getQuestionnaireProfile(userId)
                    ?.takeIf { it.userId == userId && QuestionnaireProfileRules.isValid(it) }
                _uiState.update {
                    it.copy(
                        patient = patient,
                        questionnaireProfile = profile,
                        foodQualityScore = patient?.foodQualityScore() ?: 0f,
                        categoryScores = patient?.categoryScores().orEmpty(),
                        isLoading = false,
                        isRefreshing = false
                    )
                }
            } catch (exception: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        isRefreshing = false,
                        errorMessage = exception.message?.takeIf(String::isNotBlank)
                            ?: "Unable to load dashboard data"
                    )
                }
            }
        }
    }

    fun selectRoute(route: AppRoute) {
        if (route == _uiState.value.selectedRoute) return
        _uiState.update { it.copy(selectedRoute = route) }
        routeSaveJob?.cancel()
        routeSaveJob = viewModelScope.launch {
            try {
                sessionDataSource.saveLastPage(route)
            } catch (exception: Exception) {
                _uiState.update {
                    it.copy(
                        errorMessage = exception.message?.takeIf(String::isNotBlank)
                            ?: "Unable to save the current page"
                    )
                }
            }
        }
    }

    fun logout() {
        if (_uiState.value.isLoggingOut) return
        _uiState.update { it.copy(isLoggingOut = true, errorMessage = null) }
        viewModelScope.launch {
            try {
                sessionDataSource.clearSession()
            } catch (exception: Exception) {
                _uiState.update {
                    it.copy(
                        isLoggingOut = false,
                        errorMessage = exception.message?.takeIf(String::isNotBlank)
                            ?: "Unable to log out"
                    )
                }
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null, intakeErrorMessage = null) }
    }

    private fun observeDailyIntake() {
        viewModelScope.launch {
            dailyIntakeState.collect { intakeState ->
                _uiState.update {
                    it.copy(
                        dailyIntakeSummaries = intakeState.summaries,
                        isHistoryLoading = intakeState.isHistoryLoading,
                        intakeErrorMessage = intakeState.errorMessage
                    )
                }
            }
        }
    }

    class Factory(
        private val dataSource: DashboardDataSource,
        private val sessionDataSource: DashboardSessionDataSource,
        private val userId: Int,
        private val initialRoute: AppRoute,
        private val dailyIntakeState: StateFlow<DailyIntakeUiState>
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(DashboardViewModel::class.java))
            return DashboardViewModel(
                dataSource,
                sessionDataSource,
                userId,
                initialRoute,
                dailyIntakeState
            ) as T
        }
    }
}

private fun Patient.foodQualityScore(): Float = if (sex == "Male") {
    hEIFAtotalscoreMale.toFloat()
} else {
    hEIFAtotalscoreFemale.toFloat()
}

private fun Patient.categoryScores(): List<CategoryScore> {
    val male = sex == "Male"
    fun score(maleScore: Double, femaleScore: Double): Float =
        if (male) maleScore.toFloat() else femaleScore.toFloat()

    return listOf(
        CategoryScore("Vegetables", score(vegetablesHEIFAscoreMale, vegetablesHEIFAscoreFemale)),
        CategoryScore("Fruits", score(fruitHEIFAscoreMale, fruitHEIFAscoreFemale)),
        CategoryScore("Grains & Cereals", score(grainsandcerealsHEIFAscoreMale, grainsandcerealsHEIFAscoreFemale), 5f),
        CategoryScore("Whole Grains", score(wholegrainsHEIFAscoreMale, wholegrainsHEIFAscoreFemale), 5f),
        CategoryScore("Meat & Alternatives", score(meatandalternativesHEIFAscoreMale, meatandalternativesHEIFAscoreFemale)),
        CategoryScore("Dairy", score(dairyandalternativesHEIFAscoreMale, dairyandalternativesHEIFAscoreFemale)),
        CategoryScore("Water", score(waterHEIFAscoreMale, waterHEIFAscoreFemale), 5f),
        CategoryScore("Unsaturated Fats", score(unsaturatedFatHEIFAscoreMale, unsaturatedFatHEIFAscoreFemale), 5f),
        CategoryScore("Saturated Fats", score(saturatedFatHEIFAscoreMale, saturatedFatHEIFAscoreFemale), 5f),
        CategoryScore("Sodium", score(sodiumHEIFAscoreMale, sodiumHEIFAscoreFemale)),
        CategoryScore("Sugar", score(sugarHEIFAscoreMale, sugarHEIFAscoreFemale)),
        CategoryScore("Alcohol", score(alcoholHEIFAscoreMale, alcoholHEIFAscoreFemale), 5f),
        CategoryScore("Discretionary Foods", score(discretionaryHEIFAscoreMale, discretionaryHEIFAscoreFemale))
    )
}
