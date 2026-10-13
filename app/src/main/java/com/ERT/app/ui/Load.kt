package com.ERT.app.ui

import com.ERT.app.core.ApiError

/**
 * The four states every screen can be in. Spinners are always shown with a Cancel affordance, and
 * every error carries the exact URL that failed (ApiError.uiMessage), which is what makes a broken
 * screen diagnosable from a screenshot.
 */
sealed interface Load<out T> {
    data object Idle : Load<Nothing>
    data class Busy(val url: String, val refreshing: Boolean = false) : Load<Nothing>
    data class Ready<T>(
        val value: T,
        val url: String,
        val fromCache: Boolean = false,
        val atMs: Long = System.currentTimeMillis()
    ) : Load<T>
    data class Failed(val error: ApiError) : Load<Nothing>
}

val <T> Load<T>.isBusy: Boolean get() = this is Load.Busy

fun Load<*>.failingUrl(): String? = (this as? Load.Failed)?.error?.url
