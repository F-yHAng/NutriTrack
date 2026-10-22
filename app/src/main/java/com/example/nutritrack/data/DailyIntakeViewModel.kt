package com.example.nutritrack.data

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate

data class DailyIntakeUiState(
    val selectedDate: LocalDate,
    val records: List<FoodIntake> = emptyList(),
    val summaries: List<DailyIntakeSummary> = emptyList(),
    val isLoading: Boolean = true,
    val isHistoryLoading: Boolean = true,
    val isSaving: Boolean = false,
    val isEditorOpen: Boolean = false,
    val editingRecord: FoodIntake? = null,
    val statusMessage: String? = null,
    val errorMessage: String? = null
)

class DailyIntakeViewModel(
    private val dataSource: DailyIntakeDataSource,
    private val userId: Int,
    initialDate: LocalDate = LocalDate.now()
) : ViewModel() {
    private val _uiState = MutableStateFlow(DailyIntakeUiState(selectedDate = initialDate))
    val uiState: StateFlow<DailyIntakeUiState> = _uiState.asStateFlow()

    private var selectedDateJob: Job? = null
    private var historyJob: Job? = null

    init {
        if (userId > 0) {
            observeSelectedDate(initialDate)
            observeHistory()
        } else {
            _uiState.update {
                it.copy(
                    isLoading = false,
                    isHistoryLoading = false,
                    errorMessage = "No signed-in user is available"
                )
            }
        }
    }

    fun selectDate(localDate: LocalDate) {
        if (localDate == _uiState.value.selectedDate) return
        _uiState.update {
            it.copy(
                selectedDate = localDate,
                isEditorOpen = false,
                editingRecord = null,
                statusMessage = null,
                errorMessage = null
            )
        }
        observeSelectedDate(localDate)
    }

    fun showNewEntry() {
        _uiState.update {
            it.copy(
                isEditorOpen = true,
                editingRecord = null,
                statusMessage = null,
                errorMessage = null
            )
        }
    }

    fun editRecord(recordId: Long) {
        viewModelScope.launch {
            try {
                val record = dataSource.getDailyIntake(userId, recordId)
                    ?: throw DailyIntakeAccessException()
                _uiState.update {
                    it.copy(
                        isEditorOpen = true,
                        editingRecord = record,
                        statusMessage = null,
                        errorMessage = null
                    )
                }
            } catch (exception: Exception) {
                _uiState.update { it.copy(errorMessage = exception.toDisplayMessage()) }
            }
        }
    }

    fun dismissEditor() {
        if (_uiState.value.isSaving) return
        _uiState.update { it.copy(isEditorOpen = false, editingRecord = null) }
    }

    fun save(input: DailyIntakeFormInput) {
        if (_uiState.value.isSaving) return
        _uiState.update { it.copy(isSaving = true, statusMessage = null, errorMessage = null) }
        viewModelScope.launch {
            try {
                if (input.recordId == null) {
                    dataSource.createDailyIntake(userId, input)
                } else {
                    dataSource.updateDailyIntake(userId, input)
                }
                _uiState.update {
                    it.copy(
                        isSaving = false,
                        isEditorOpen = false,
                        editingRecord = null,
                        statusMessage = "Daily intake saved"
                    )
                }
            } catch (exception: Exception) {
                _uiState.update {
                    it.copy(isSaving = false, errorMessage = exception.toDisplayMessage())
                }
            }
        }
    }

    fun clearFeedback() {
        _uiState.update { it.copy(statusMessage = null, errorMessage = null) }
    }

    private fun observeSelectedDate(localDate: LocalDate) {
        selectedDateJob?.cancel()
        _uiState.update { it.copy(isLoading = true, records = emptyList(), errorMessage = null) }
        selectedDateJob = viewModelScope.launch {
            dataSource.observeDailyIntakes(userId, localDate)
                .catch { exception ->
                    _uiState.update {
                        it.copy(isLoading = false, errorMessage = exception.toDisplayMessage())
                    }
                }
                .collect { records ->
                    _uiState.update { it.copy(records = records, isLoading = false) }
                }
        }
    }

    private fun observeHistory() {
        historyJob?.cancel()
        historyJob = viewModelScope.launch {
            dataSource.observeDailyIntakeSummaries(userId)
                .catch { exception ->
                    _uiState.update {
                        it.copy(
                            isHistoryLoading = false,
                            errorMessage = exception.toDisplayMessage()
                        )
                    }
                }
                .collect { summaries ->
                    _uiState.update { it.copy(summaries = summaries, isHistoryLoading = false) }
                }
        }
    }

    private fun Throwable.toDisplayMessage(): String =
        message?.takeIf(String::isNotBlank) ?: "Unable to load daily intake data"

    class Factory(
        private val dataSource: DailyIntakeDataSource,
        private val userId: Int
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(DailyIntakeViewModel::class.java))
            return DailyIntakeViewModel(dataSource, userId) as T
        }
    }
}
