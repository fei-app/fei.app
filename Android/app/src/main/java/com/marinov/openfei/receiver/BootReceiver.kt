package com.marinov.openfei.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.marinov.openfei.service.BackgroundService

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            BackgroundService.start(context)
        }
    }
}