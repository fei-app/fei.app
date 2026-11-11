package com.marinov.openfei.background

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class SyncAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val type = intent.getStringExtra(SyncScheduler.EXTRA_SYNC_TYPE) ?: return
        Log.d("SyncAlarmReceiver", "Alarme recebido: $type")

        // ★ BUG C: re-arma a PRÓXIMA janela imediatamente.
        // Sem isto o alarme one-shot morre e o sync só volta quando o app abrir.
        when (type) {
            SyncScheduler.TYPE_FEI -> SyncScheduler.scheduleNextFei(app)
            SyncScheduler.TYPE_MOODLE -> SyncScheduler.scheduleNextMoodle(app)
        }

        PeriodicSyncWorker.enqueueOnce(app, type)
    }
}