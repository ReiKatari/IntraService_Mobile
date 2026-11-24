package com.lantaservice.intra.ui.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.lantaservice.intra.data.repository.IntraNetworkException
import com.lantaservice.intra.data.repository.IntraRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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

    private var persistJob: Job? = null
    private val persistLock = Any()
    private var lastPersistedState: Triple<String, String, Boolean>? = null

    private val _uiState = MutableStateFlow(
        run {
            val saved = repository.loadPrefilledCredentials()
            lastPersistedState = saved?.let { Triple(it.login, it.password, it.rememberMe) }
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
        if (_uiState.value.rememberMe) {
            scheduleRememberedPersist()
        }
    }

    fun onPasswordChange(value: String) {
        _uiState.update { it.copy(password = value, errorMessage = null, showVpnSettingsHint = false) }
        if (_uiState.value.rememberMe) {
            scheduleRememberedPersist()
        }
    }

    fun onRememberMeChange(value: Boolean) {
        persistJob?.cancel()
        _uiState.update { it.copy(rememberMe = value) }
        val state = _uiState.value
        if (value) {
            persistNow(state.login, state.password, true)
        } else {
            persistNow("", "", false)
        }
    }

    fun syncSavedCredentialsIfNeeded() {
        val saved = repository.loadPrefilledCredentials() ?: return
        if (!saved.rememberMe) return
        val current = _uiState.value
        if (current.login.isBlank() && current.password.isEmpty()) {
            _uiState.update {
                it.copy(
                    login = saved.login,
                    password = saved.password,
                    rememberMe = true
                )
            }
            synchronized(persistLock) {
                lastPersistedState = Triple(saved.login, saved.password, true)
            }
        }
    }

    fun flushRememberedCredentials() {
        persistJob?.cancel()
        val state = _uiState.value
        if (state.rememberMe) {
            persistNow(state.login, state.password, true)
        } else if (lastPersistedState?.third != false) {
            persistNow("", "", false)
        }
    }

    fun submit() {
        val state = _uiState.value
        if (state.isLoading) return
        val cleanLogin = state.login.trim()
        if (cleanLogin.isEmpty() || state.password.isEmpty()) {
            _uiState.update { it.copy(errorMessage = "Укажите логин или E-mail и пароль", showVpnSettingsHint = false) }
            return
        }
        persistJob?.cancel()
        if (state.rememberMe) {
            persistNow(cleanLogin, state.password, true)
        } else {
            persistNow("", "", false)
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null, showVpnSettingsHint = false) }
            val result = repository.login(
                login = cleanLogin,
                password = state.password,
                rememberMe = state.rememberMe
            )
            result.onSuccess {
                val (nextLogin, nextPassword) = resolvePostLoginFields(
                    login = cleanLogin,
                    password = state.password,
                    rememberMe = state.rememberMe
                )
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        login = nextLogin,
                        password = nextPassword,
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

    override fun onCleared() {
        flushRememberedCredentials()
        super.onCleared()
    }

    private fun scheduleRememberedPersist() {
        persistJob?.cancel()
        persistJob = viewModelScope.launch(Dispatchers.IO) {
            delay(150)
            val snapshot = _uiState.value
            if (snapshot.rememberMe) {
                persistNow(snapshot.login, snapshot.password, true)
            }
        }
    }

    private fun persistNow(login: String, password: String, rememberMe: Boolean) {
        synchronized(persistLock) {
            val target = Triple(login, password, rememberMe)
            if (lastPersistedState == target) return
            repository.saveRememberedCredentials(login, password, rememberMe)
            lastPersistedState = target
        }
    }

    companion object {
        fun resolvePostLoginFields(
            login: String,
            password: String,
            rememberMe: Boolean
        ): Pair<String, String> {
            return if (rememberMe) {
                login to password
            } else {
                "" to ""
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
