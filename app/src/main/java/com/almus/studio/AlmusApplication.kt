package com.almus.studio

import android.app.Application

/**
 * Almus Studio is offline-only: this Application class intentionally contains
 * no network client, no analytics SDK, and no cloud initialization of any kind.
 */
class AlmusApplication : Application() {
    override fun onCreate() {
        super.onCreate()
    }
}
