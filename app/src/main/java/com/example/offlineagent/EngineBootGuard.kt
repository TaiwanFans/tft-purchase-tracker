package com.example.offlineagent

import android.content.Context

class EngineBootGuard(context: Context) {
    private val prefs = context.getSharedPreferences("engine_boot_guard", Context.MODE_PRIVATE)

    fun begin(backend: String) {
        prefs.edit()
            .putBoolean(KEY_IN_PROGRESS, true)
            .putString(KEY_BACKEND, backend)
            .commit()
    }

    fun success(backend: String) {
        prefs.edit()
            .putBoolean(KEY_IN_PROGRESS, false)
            .remove(KEY_BACKEND)
            .putString(KEY_LAST_SUCCESS, backend)
            .commit()
    }

    fun clearInProgress() {
        prefs.edit()
            .putBoolean(KEY_IN_PROGRESS, false)
            .remove(KEY_BACKEND)
            .commit()
    }

    fun crashedBackendIfAny(): String? {
        if (!prefs.getBoolean(KEY_IN_PROGRESS, false)) return null
        return prefs.getString(KEY_BACKEND, null) ?: "UNKNOWN"
    }

    fun lastSuccessfulBackend(): String? = prefs.getString(KEY_LAST_SUCCESS, null)

    fun reset() {
        prefs.edit().clear().commit()
    }

    companion object {
        private const val KEY_IN_PROGRESS = "engine_boot_in_progress"
        private const val KEY_BACKEND = "engine_boot_backend"
        private const val KEY_LAST_SUCCESS = "last_success_backend"
    }
}
