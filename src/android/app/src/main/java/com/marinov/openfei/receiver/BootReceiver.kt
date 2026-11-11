package com.marinov.openfei.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.marinov.openfei.background.SyncScheduler

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            // ★ BUG 10: não iniciar FGS direto do boot em Android 15+.
            // Delegamos ao SyncScheduler, que usa WorkManager + AlarmManager.
            SyncScheduler.onBoot(context.applicationContext)
        }
    }
}