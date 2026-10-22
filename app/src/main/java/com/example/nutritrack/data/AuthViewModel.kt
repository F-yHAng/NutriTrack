package com.example.nutritrack.data

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class AuthMode {
    LOGIN,
    REGISTER
}

enum class RegistrationCredentialStatus {
    IDLE,
    VALIDATING,
    VALID,
    INVALID,
    ERROR
}

data class AuthNavigation(
    val eventId: Long,
    val userId: Int,
    val phoneNumber: String,
    val hasCompletedQuestionnaire: Boolean,
    val lastPage: AppRoute
)

data class AuthUiState(
    val mode: AuthMode = AuthMode.LOGIN,
    val availableUserIds: List<Int> = emptyList(),
    val unregisteredUserIds: List<Int> = emptyList(),
    val selectedUserId: Int = 0,
    val selectedUserIsRegistered: Boolean = false,
    val selectedAccount: UserAccount? = null,
    val credentialStatus: RegistrationCredentialStatus = RegistrationCredentialStatus.IDLE,
    val isLoadingUsers: Boolean = true,
    val isCheckingUser: Boolean = false,
    val isSubmitting: Boolean = false,
    val errorMessage: String? = null,
    val navigation: AuthNavigation? = null
) {
    val visibleUserIds: List<Int>
        get() = if (mode == AuthMode.LOGIN) availableUserIds else unregisteredUserIds
}

interface AuthDataSource {
    suspend fun initializeDatabase(): SeedInitializationResult
    fun observeAvailableUserIds(): Flow<List<Int>>
    suspend fun isUserRegistered(userId: Int): Boolean
    suspend fun getUserAccount(userId: Int): UserAccount?
    suspend fun validateLogin(userId: Int, password: String): UserAccount?
    suspend fun isRegistrationIdentityValid(userId: Int, phoneNumber: String): Boolean
    suspend fun registerUser(userId: Int, phoneNumber: String, name: String, password: String): Boolean
    suspend fun updateLastLoginTime(userId: Int)
    suspend fun migrateLegacyQuestionnaire(userId: Int): LegacyQuestionnaireMigrationResult
    suspend fun hasCompletedQuestionnaire(userId: Int): Boolean
}

interface AuthSessionDataSource {
    suspend fun saveLoginSession(userId: Int)
}

class AuthViewModel(
    private val dataSource: AuthDataSource,
    private val sessionDataSource: AuthSessionDataSource
) : ViewModel() {
    private val _uiState = MutableStateFlow(AuthUiState())
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    private var userCheckJob: Job? = null
    private var credentialValidationJob: Job? = null
    private var sessionResolutionJob: Job? = null
    private var nextEventId = 1L

    init {
        loadUsers()
    }

    fun retryLoadingUsers() {
        if (_uiState.value.isLoadingUsers) return
        loadUsers()
    }

    fun showLogin() {
        _uiState.update {
            it.copy(
                mode = AuthMode.LOGIN,
                credentialStatus = RegistrationCredentialStatus.IDLE,
                errorMessage = null
            )
        }
        refreshSelectedUser()
    }

    fun showRegistration() {
        _uiState.update {
            val selected = it.selectedUserId.takeIf(it.unregisteredUserIds::contains) ?: 0
            it.copy(
                mode = AuthMode.REGISTER,
                selectedUserId = selected,
                selectedUserIsRegistered = false,
                selectedAccount = null,
                credentialStatus = RegistrationCredentialStatus.IDLE,
                errorMessage = null
            )
        }
    }

    fun selectUser(userId: Int) {
        if (userId !in _uiState.value.visibleUserIds) return
        credentialValidationJob?.cancel()
        _uiState.update {
            it.copy(
                selectedUserId = userId,
                selectedUserIsRegistered = false,
                selectedAccount = null,
                credentialStatus = RegistrationCredentialStatus.IDLE,
                errorMessage = null
            )
        }
        refreshSelectedUser()
    }

    fun validateRegistrationCredentials(phoneNumber: String) {
        credentialValidationJob?.cancel()
        val userId = _uiState.value.selectedUserId
        if (userId <= 0 || phoneNumber.isBlank()) {
            _uiState.update { it.copy(credentialStatus = RegistrationCredentialStatus.IDLE) }
            return
        }
        _uiState.update {
            it.copy(credentialStatus = RegistrationCredentialStatus.VALIDATING, errorMessage = null)
        }
        credentialValidationJob = viewModelScope.launch {
            try {
                val identityIsValid = dataSource.isRegistrationIdentityValid(userId, phoneNumber)
                val duplicate = dataSource.isUserRegistered(userId)
                _uiState.update {
                    it.copy(
                        credentialStatus = if (identityIsValid && !duplicate) {
                            RegistrationCredentialStatus.VALID
                        } else {
                            RegistrationCredentialStatus.INVALID
                        }
                    )
                }
            } catch (exception: Exception) {
                _uiState.update {
                    it.copy(
                        credentialStatus = RegistrationCredentialStatus.ERROR,
                        errorMessage = exception.toAuthMessage("Unable to validate credentials")
                    )
                }
            }
        }
    }

    fun login(password: String) {
        if (_uiState.value.isSubmitting) return
        val userId = _uiState.value.selectedUserId
        val validationMessage = when {
            userId <= 0 -> "Please select a User ID"
            !_uiState.value.selectedUserIsRegistered -> "This user is not registered. Please register first."
            password.isBlank() -> "Please enter your password"
            else -> null
        }
        if (validationMessage != null) {
            _uiState.update { it.copy(errorMessage = validationMessage) }
            return
        }

        _uiState.update { it.copy(isSubmitting = true, errorMessage = null) }
        viewModelScope.launch {
            try {
                val account = dataSource.validateLogin(userId, password)
                if (account == null) {
                    _uiState.update {
                        it.copy(isSubmitting = false, errorMessage = "Invalid password. Please try again.")
                    }
                    return@launch
                }
                dataSource.updateLastLoginTime(userId)
                sessionDataSource.saveLoginSession(userId)
                publishDestination(userId, account.phoneNumber, AppRoute.HOME)
            } catch (exception: Exception) {
                _uiState.update {
                    it.copy(
                        isSubmitting = false,
                        errorMessage = exception.toAuthMessage("Login failed")
                    )
                }
            }
        }
    }

    fun register(
        phoneNumber: String,
        name: String,
        password: String,
        confirmPassword: String
    ) {
        if (_uiState.value.isSubmitting) return
        val userId = _uiState.value.selectedUserId
        val trimmedName = name.trim()
        val validationMessage = when {
            userId <= 0 -> "Please select a User ID"
            phoneNumber.isBlank() -> "Please enter your phone number"
            _uiState.value.credentialStatus != RegistrationCredentialStatus.VALID ->
                "Please ensure your credentials are validated before proceeding"
            trimmedName.isEmpty() -> "Please enter your full name"
            password.isEmpty() -> "Please enter a password"
            !PasswordValidator.validatePassword(password).isValid ->
                PasswordValidator.validatePassword(password).errorMessage
            !PasswordValidator.validatePasswordConfirmation(password, confirmPassword).isValid ->
                PasswordValidator.validatePasswordConfirmation(password, confirmPassword).errorMessage
            else -> null
        }
        if (validationMessage != null) {
            _uiState.update { it.copy(errorMessage = validationMessage) }
            return
        }

        _uiState.update { it.copy(isSubmitting = true, errorMessage = null) }
        viewModelScope.launch {
            try {
                if (dataSource.isUserRegistered(userId)) {
                    _uiState.update {
                        it.copy(
                            isSubmitting = false,
                            credentialStatus = RegistrationCredentialStatus.INVALID,
                            errorMessage = "This account has already been registered."
                        )
                    }
                    return@launch
                }
                val success = dataSource.registerUser(userId, phoneNumber, trimmedName, password)
                if (!success) {
                    _uiState.update {
                        it.copy(isSubmitting = false, errorMessage = "Registration failed. Please try again.")
                    }
                    return@launch
                }
                sessionDataSource.saveLoginSession(userId)
                publishDestination(userId, phoneNumber, AppRoute.HOME)
            } catch (exception: Exception) {
                _uiState.update {
                    it.copy(
                        isSubmitting = false,
                        errorMessage = exception.toAuthMessage("Registration failed")
                    )
                }
            }
        }
    }

    fun resolveLoggedInSession(userId: Int, lastPage: AppRoute) {
        if (userId <= 0 || sessionResolutionJob?.isActive == true) return
        val pending = _uiState.value.navigation
        if (pending?.userId == userId) return
        sessionResolutionJob = viewModelScope.launch {
            try {
                val account = dataSource.getUserAccount(userId)
                publishDestination(userId, account?.phoneNumber.orEmpty(), lastPage)
            } catch (exception: Exception) {
                _uiState.update {
                    it.copy(errorMessage = exception.toAuthMessage("Unable to restore the signed-in user"))
                }
            }
        }
    }

    fun navigationHandled(eventId: Long) {
        _uiState.update { state ->
            if (state.navigation?.eventId == eventId) state.copy(navigation = null) else state
        }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    private fun loadUsers() {
        _uiState.update { it.copy(isLoadingUsers = true, errorMessage = null) }
        viewModelScope.launch {
            try {
                dataSource.initializeDatabase()
                val ids = dataSource.observeAvailableUserIds().first().distinct().sorted()
                val unregistered = ids.filterNot { dataSource.isUserRegistered(it) }
                _uiState.update {
                    it.copy(
                        availableUserIds = ids,
                        unregisteredUserIds = unregistered,
                        isLoadingUsers = false
                    )
                }
            } catch (exception: Exception) {
                _uiState.update {
                    it.copy(
                        availableUserIds = emptyList(),
                        unregisteredUserIds = emptyList(),
                        isLoadingUsers = false,
                        errorMessage = exception.toAuthMessage("Unable to load user IDs")
                    )
                }
            }
        }
    }

    private fun refreshSelectedUser() {
        userCheckJob?.cancel()
        val state = _uiState.value
        if (state.mode != AuthMode.LOGIN || state.selectedUserId <= 0) return
        val userId = state.selectedUserId
        _uiState.update { it.copy(isCheckingUser = true, errorMessage = null) }
        userCheckJob = viewModelScope.launch {
            try {
                val registered = dataSource.isUserRegistered(userId)
                val account = if (registered) dataSource.getUserAccount(userId) else null
                _uiState.update {
                    it.copy(
                        selectedUserIsRegistered = registered,
                        selectedAccount = account,
                        isCheckingUser = false
                    )
                }
            } catch (exception: Exception) {
                _uiState.update {
                    it.copy(
                        isCheckingUser = false,
                        errorMessage = exception.toAuthMessage("Unable to check this account")
                    )
                }
            }
        }
    }

    private suspend fun publishDestination(userId: Int, phoneNumber: String, lastPage: AppRoute) {
        val hasCompletedQuestionnaire = try {
            dataSource.migrateLegacyQuestionnaire(userId)
            dataSource.hasCompletedQuestionnaire(userId)
        } catch (_: Exception) {
            false
        }
        _uiState.update {
            it.copy(
                isSubmitting = false,
                navigation = AuthNavigation(
                    eventId = nextEventId++,
                    userId = userId,
                    phoneNumber = phoneNumber,
                    hasCompletedQuestionnaire = hasCompletedQuestionnaire,
                    lastPage = lastPage
                )
            )
        }
    }

    private fun Throwable.toAuthMessage(fallback: String): String =
        message?.takeIf(String::isNotBlank)?.let { "$fallback: $it" } ?: fallback

    class Factory(
        private val dataSource: AuthDataSource,
        private val sessionDataSource: AuthSessionDataSource
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(AuthViewModel::class.java))
            return AuthViewModel(dataSource, sessionDataSource) as T
        }
    }
}
