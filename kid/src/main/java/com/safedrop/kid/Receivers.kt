package com.safedrop.kid

import android.app.admin.DeviceAdminReceiver
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/** Registering as device admin makes uninstalling the app require the admin to be removed first. */
class AdminReceiver : DeviceAdminReceiver() {
    override fun onDisabled(context: Context, intent: Intent) {
        KidState.init(context.applicationContext as android.app.Application)
        Store.addLog("Device admin was turned off on the child's phone")
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        KidState.init(context.applicationContext as android.app.Application)
        if (KidState.configured()) {
            ContextCompat.startForegroundService(context, Intent(context, MonitorService::class.java))
        }
    }
}
