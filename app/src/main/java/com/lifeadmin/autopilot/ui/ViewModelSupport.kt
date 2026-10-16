package com.lifeadmin.autopilot.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.lifeadmin.autopilot.core.AppClock
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.time.Instant

inline fun <reified VM : ViewModel> factoryOf(crossinline create: () -> VM): ViewModelProvider.Factory =
    viewModelFactory { initializer { create() } }

/** Emits the current time periodically so relative labels ("Today 18:00", overdue) stay fresh. */
fun tickerFlow(clock: AppClock, periodMillis: Long = 30_000): Flow<Instant> = flow {
    while (true) {
        emit(clock.now())
        delay(periodMillis)
    }
}

/** User-facing message for an exception, never echoing task contents. */
fun Throwable.userMessage(): String = when (this) {
    is com.lifeadmin.autopilot.data.repository.ValidationException -> message ?: "Invalid input"
    is IllegalArgumentException -> message ?: "That action isn't allowed"
    is NoSuchElementException -> "That task no longer exists"
    else -> "Something went wrong"
}
