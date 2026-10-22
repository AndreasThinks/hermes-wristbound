package dev.wristbound.companion.android

import android.app.Application

class WristboundApp : Application() {
    val hub: Hub by lazy { Hub(this) }

    override fun onCreate() {
        super.onCreate()
        if (hub.settings.serviceEnabled && hub.settings.isConfigured) WristboundService.start(this)
    }
}
