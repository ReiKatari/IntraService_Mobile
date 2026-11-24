package com.lantaservice.intra.ui.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.lantaservice.intra.data.repository.IntraNetworkException
import com.lantaservice.intra.data.repository.IntraRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LoginUiState(
    val serverHost: String,
    val login: String = "",
    val password: String = "",
    val rememberMe: Boolean = true,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val showVpnSettingsHint: Boolean = false
)

class LoginViewModel(
    private val repository: IntraRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        run {
            val saved = repository.loadPrefilledCredentials()
            LoginUiState(
                serverHost = repository.serverDisplayHost(),
                login = saved?.login.orEmpty(),
                password = saved?.password.orEmpty(),
                rememberMe = saved?.rememberMe ?: true
            )
        }
    )
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

    fun onLoginChange(value: String) {
        _uiState.update { it.copy(login = value, errorMessage = null, showVpnSettingsHint = false) }
    }

    fun onPasswordChange(value: String) {
        _uiState.update { it.copy(password = value, errorMessage = null, showVpnSettingsHint = false) }
    }

    fun onRememberMeChange(value: Boolean) {
        _uiState.update { it.copy(rememberMe = value) }
    }

    fun submit() {
        val state = _uiState.value
        if (state.isLoading) return
        if (state.login.isBlank() || state.password.isEmpty()) {
            _uiState.update { it.copy(errorMessage = "Укажите логин или E-mail и пароль", showVpnSettingsHint = false) }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null, showVpnSettingsHint = false) }
            val result = repository.login(
                login = state.login,
                password = state.password,
                rememberMe = state.rememberMe
            )
            result.onSuccess {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        password = "",
                        errorMessage = null,
                        showVpnSettingsHint = false
                    )
                }
            }.onFailure { err ->
                val isVpn = (err as? IntraNetworkException)?.isVpnBlocked == true
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = err.message ?: "Не удалось выполнить вход",
                        showVpnSettingsHint = isVpn
                    )
                }
            }
        }
    }

    class Factory(private val repository: IntraRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return LoginViewModel(repository) as T
        }
    }
}
