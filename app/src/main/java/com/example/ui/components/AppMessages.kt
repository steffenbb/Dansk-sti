package com.example.ui.components

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** A short message for the app's themed Snackbar host (replaces system Toasts shown from full screens). */
data class AppMessage(val text: String, val long: Boolean = false)

/**
 * App-wide message channel collected by the Snackbar host in MainActivity (UI audit batch 1).
 * Toasts shown from bottom sheets and dialogs stay Toasts: those open in their own window, where a
 * Snackbar would be hidden behind them.
 */
object AppMessages {
    private val _messages = MutableSharedFlow<AppMessage>(extraBufferCapacity = 8)
    val messages: SharedFlow<AppMessage> = _messages.asSharedFlow()

    fun show(text: String, long: Boolean = false) {
        if (text.isNotBlank()) _messages.tryEmit(AppMessage(text, long))
    }
}
