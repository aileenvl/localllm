package com.localllm.app

import android.app.Application

/**
 * Single Application entry point. Today it just installs a global uncaught
 * exception logger so crashes surface in the Console tab instead of vanishing
 * into the system log. This is the natural place to wire in crash reporting,
 * DI, or a logging library later.
 */
class LocalLLMApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, ex ->
            try {
                LogManager.e("CRASH", "Uncaught on ${thread.name}", ex)
            } catch (_: Throwable) {
                // Never swallow the crash because we couldn't log it.
            }
            previous?.uncaughtException(thread, ex)
        }

        LogManager.i("App", "LocalLLM v${BuildConfig.VERSION_NAME} starting")
    }
}
