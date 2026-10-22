package com.example.nutritrack.data

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class FruitNutritionUiState(
    val query: String = "",
    val status: FruitSearchStatus = FruitSearchStatus.Idle
)

sealed interface FruitSearchStatus {
    data object Idle : FruitSearchStatus
    data object Loading : FruitSearchStatus
    data class Success(val fruit: FruitNutrition) : FruitSearchStatus
    data class Empty(val message: String) : FruitSearchStatus
    data class Error(val message: String) : FruitSearchStatus
}

class FruitNutritionViewModel(
    private val gateway: FruitNutritionGateway
) : ViewModel() {
    private val _uiState = MutableStateFlow(FruitNutritionUiState())
    val uiState: StateFlow<FruitNutritionUiState> = _uiState.asStateFlow()

    private var searchJob: Job? = null
    private var lastSubmittedQuery: String? = null

    fun queryChanged(query: String) {
        _uiState.update { state ->
            state.copy(
                query = query,
                status = when (state.status) {
                    is FruitSearchStatus.Error, is FruitSearchStatus.Empty -> FruitSearchStatus.Idle
                    else -> state.status
                }
            )
        }
    }

    fun search() {
        startSearch(_uiState.value.query.trim())
    }

    fun retry() {
        startSearch(lastSubmittedQuery.orEmpty())
    }

    private fun startSearch(query: String) {
        if (searchJob?.isActive == true) return
        if (query.isBlank()) {
            _uiState.update {
                it.copy(status = FruitSearchStatus.Error("Enter a fruit name"))
            }
            return
        }
        lastSubmittedQuery = query
        _uiState.update { it.copy(status = FruitSearchStatus.Loading) }
        searchJob = viewModelScope.launch {
            val result = try {
                gateway.search(query)
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                FruitNutritionResult.Failure(FruitNutritionFailure.UNKNOWN)
            }
            _uiState.update { it.copy(status = result.toUiStatus()) }
        }
    }

    private fun FruitNutritionResult.toUiStatus(): FruitSearchStatus = when (this) {
        is FruitNutritionResult.Found -> FruitSearchStatus.Success(fruit)
        FruitNutritionResult.Empty -> FruitSearchStatus.Empty(
            "Fruit not found. Try a different name."
        )
        is FruitNutritionResult.Failure -> FruitSearchStatus.Error(
            when (reason) {
                FruitNutritionFailure.TIMEOUT -> "The request timed out. Try again."
                FruitNutritionFailure.NO_NETWORK -> "No network connection. Check your connection and try again."
                FruitNutritionFailure.SERVICE_UNAVAILABLE -> "Fruit service is unavailable. Try again later."
                FruitNutritionFailure.INVALID_RESPONSE -> "Fruit data could not be read. Try again."
                FruitNutritionFailure.REQUEST_REJECTED -> "Fruit search could not be completed. Try again."
                FruitNutritionFailure.UNKNOWN -> "Unable to search for fruit. Try again."
            }
        )
    }

    class Factory(
        private val gateway: FruitNutritionGateway
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(FruitNutritionViewModel::class.java))
            return FruitNutritionViewModel(gateway) as T
        }
    }
}
