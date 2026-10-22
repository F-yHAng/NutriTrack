package com.example.nutritrack.data

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

class GenAIViewModel(
    private val aiGateway: AiGateway
) : ViewModel() {
    private val _uiState = MutableStateFlow<UiState>(UiState.Initial)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private var requestJob: Job? = null

    fun sendPrompt(prompt: String) {
        if (requestJob?.isActive == true) return
        if (prompt.isBlank()) {
            _uiState.value = UiState.Error("Please enter a prompt.")
            return
        }

        _uiState.value = UiState.Loading
        requestJob = viewModelScope.launch {
            try {
                val result = withTimeout(REQUEST_TIMEOUT_MILLIS) {
                    aiGateway.generate(AiRequest(prompt.trim()))
                }
                _uiState.value = result.toUiState()
            } catch (_: TimeoutCancellationException) {
                _uiState.value = AiError.Timeout.toUiState()
            } catch (exception: CancellationException) {
                _uiState.value = AiError.Cancelled.toUiState()
                throw exception
            } catch (_: TimeoutException) {
                _uiState.value = AiError.Timeout.toUiState()
            } catch (_: Exception) {
                _uiState.value = AiError.Sdk.toUiState()
            }
        }
    }

    fun cancelRequest() {
        val activeRequest = requestJob?.takeIf(Job::isActive) ?: return
        _uiState.value = AiError.Cancelled.toUiState()
        activeRequest.cancel()
    }

    private fun AiResult.toUiState(): UiState = when (this) {
        is AiResult.Success -> {
            val text = response.text.trim()
            if (text.isEmpty()) {
                AiError.EmptyResponse.toUiState()
            } else {
                UiState.Success(outputText = text, modelName = response.modelName)
            }
        }
        is AiResult.Failure -> error.toUiState()
    }

    private fun AiError.toUiState(): UiState.Error = UiState.Error(
        when (this) {
            AiError.ServiceConfigurationPending ->
                "服务配置待完成，请完成 App Check 注册后重试。"
            AiError.EmptyResponse -> "No response received from AI"
            AiError.Timeout -> "AI request timed out. Please try again."
            AiError.Network -> "Unable to reach AI service. Check your connection and try again."
            AiError.Sdk -> "AI service error. Please try again."
            AiError.Cancelled -> "AI request cancelled."
        }
    )

    class Factory(
        private val aiGateway: AiGateway
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(GenAIViewModel::class.java))
            return GenAIViewModel(aiGateway) as T
        }
    }

    companion object {
        internal const val REQUEST_TIMEOUT_MILLIS = 30_000L
    }
}
