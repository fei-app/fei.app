package com.marinov.openfei

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_LOCKED_BOOT_COMPLETED) {

            Log.d("BootReceiver", "Dispositivo reiniciado. Recarregando Workers em background...")

            try {
                // Inicia os workers forçadamente para garantir que o sistema não os "esqueça"
                WorkerManagerHelper.iniciarWorkers(context)
            } catch (e: Exception) {
                Log.e("BootReceiver", "Falha ao reagendar workers no boot", e)
            }
        }
    }
}