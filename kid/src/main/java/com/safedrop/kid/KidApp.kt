package com.safedrop.kid

import android.app.Application

class KidApp : Application() {
    val nearby: NearbyManager by lazy { NearbyManager(this) }

    override fun onCreate() {
        super.onCreate()
        KidState.init(this)
        Store.load()
    }
}
