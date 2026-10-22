package com.example.nutritrack.data

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

// Only display fields cross the UI boundary; password material stays in the data layer.
data class SettingsAccount(val userId: Int, val name: String, val phoneNumber: String)
data class SettingsUiState(
    val account: SettingsAccount? = null,
    val isLoading: Boolean = true,
    val errorMessage: String? = null
)

interface SettingsDataSource {
    suspend fun getSettingsAccount(userId: Int): SettingsAccount?
}

class SettingsViewModel(
    private val dataSource: SettingsDataSource,
    private val userId: Int
) : ViewModel() {
    private val state = MutableStateFlow(SettingsUiState())
    val uiState = state.asStateFlow()
    private var loadJob: Job? = null

    init { refresh() }

    fun refresh() {
        if (loadJob?.isActive == true) return
        state.value = SettingsUiState()
        loadJob = viewModelScope.launch {
            try {
                val account = if (userId > 0) dataSource.getSettingsAccount(userId) else null
                state.value = if (account != null && account.userId == userId) {
                    SettingsUiState(account = account, isLoading = false)
                } else {
                    SettingsUiState(isLoading = false, errorMessage = "Account details are unavailable. Try again or sign in again.")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                state.value = SettingsUiState(isLoading = false, errorMessage = "Unable to load account details. Please try again.")
            }
        }
    }

    class Factory(private val dataSource: SettingsDataSource, private val userId: Int) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(SettingsViewModel::class.java))
            return SettingsViewModel(dataSource, userId) as T
        }
    }
}
