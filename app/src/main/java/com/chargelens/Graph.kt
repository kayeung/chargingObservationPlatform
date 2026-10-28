package com.chargelens

import android.content.Context
import com.chargelens.data.LiveRepository
import com.chargelens.data.Prefs
import com.chargelens.data.SampleStore
import com.chargelens.protocol.CapabilityProbe
import com.chargelens.protocol.DeviceProfile
import com.chargelens.protocol.ProtocolEngine

/**
 * Hand-rolled service locator. The app is small enough that a DI framework would only add build
 * time and reflection.
 */
object Graph {

    private lateinit var appContext: Context

    fun init(context: Context) {
        if (!::appContext.isInitialized) {
            appContext = context.applicationContext
        }
    }

    val context: Context get() = appContext
    val prefs: Prefs by lazy { Prefs(appContext) }
    val store: SampleStore by lazy { SampleStore(appContext) }
    val repo: LiveRepository by lazy { LiveRepository() }
    val engine: ProtocolEngine by lazy { ProtocolEngine() }
    val device: DeviceProfile by lazy { DeviceProfile.current() }
    val capabilityProbe: CapabilityProbe by lazy { CapabilityProbe(appContext) }
}
