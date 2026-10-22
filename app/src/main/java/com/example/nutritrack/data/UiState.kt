package com.example.nutritrack.data

/**
 * UiState sealed interface for managing AI generation states
 */
sealed interface UiState {
    /**
     * Empty state when the screen is first shown
     */
    object Initial : UiState

    /**
     * Still loading
     */
    object Loading : UiState

    /**
     * Text has been generated
     */
    data class Success(
        val outputText: String,
        val modelName: String
    ) : UiState

    /**
     * There was an error generating text
     */
    data class Error(val errorMessage: String) : UiState
}
