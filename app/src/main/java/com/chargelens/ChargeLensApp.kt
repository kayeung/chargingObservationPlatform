package com.chargelens

import android.app.Application

class ChargeLensApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Graph.init(this)
    }
}
