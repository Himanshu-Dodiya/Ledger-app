package com.ledger.collector.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ledger.collector.data.auth.AuthRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Login/signup state. On a successful sign-in the Supabase session flips to Authenticated,
 * which the host (MainActivity) observes to swap in the main app — so there is no manual
 * navigation here.
 */
class LoginViewModel(private val auth: AuthRepository) : ViewModel() {

    data class UiState(
        val loading: Boolean = false,
        val error: String? = null,
        val info: String? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    fun signIn(email: String, password: String) = run {
        auth.signInEmail(email.trim(), password)
    }

    fun signUp(email: String, password: String) = run(
        onSuccess = {
            if (auth.currentUserId() == null) {
                _state.value = UiState(info = "Check your email to confirm, then sign in.")
            }
        }
    ) {
        auth.signUpEmail(email.trim(), password)
    }

    fun signInGoogle(idToken: String, rawNonce: String) = run {
        auth.signInGoogle(idToken, rawNonce)
    }

    fun forgotPassword(email: String) = run(
        onSuccess = { _state.value = UiState(info = "Password reset email sent. Check your inbox.") }
    ) {
        auth.requestPasswordReset(email.trim())
    }

    /** Surface an error raised by the Google credential flow (cancellation, misconfig, etc.). */
    fun onGoogleError(message: String) {
        _state.value = UiState(error = message)
    }

    fun setLoading(loading: Boolean) {
        _state.value = _state.value.copy(loading = loading, error = null, info = null)
    }

    private fun run(onSuccess: () -> Unit = {}, block: suspend () -> Unit) {
        if (_state.value.loading) return
        _state.value = UiState(loading = true)
        viewModelScope.launch {
            try {
                block()
                onSuccess()
                if (_state.value.info == null) _state.value = UiState()
            } catch (e: Exception) {
                _state.value = UiState(error = e.message ?: "Something went wrong")
            }
        }
    }
}
