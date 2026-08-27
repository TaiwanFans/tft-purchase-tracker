package com.example.offlineagent

import android.app.Application

class LocalPilotApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        val original = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                getSharedPreferences("diagnostics", MODE_PRIVATE)
                    .edit()
                    .putString("last_crash", throwable.stackTraceToString())
                    .putLong("last_crash_time", System.currentTimeMillis())
                    .commit()
            }
            original?.uncaughtException(thread, throwable)
        }
    }
}
