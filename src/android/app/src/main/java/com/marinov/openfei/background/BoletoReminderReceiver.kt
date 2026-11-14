package com.marinov.openfei.background

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Receiver dos lembretes de boleto ("Lembrar mais tarde").
 *
 * Fluxo:
 *  1. ACTION_SHOW_OPTIONS → reposta a notificação de boleto EXPANDIDA,
 *     com as opções 1h / 2h / 3h / 6h / 12h (layout customizado).
 *  2. ACTION_SNOOZE (extra horas) → cancela a notificação e agenda o
 *     lembrete via AlarmManager.
 *  3. ACTION_REMIND → o alarme disparou (ou o usuário tocou em "Voltar"):
 *     reposta a notificação original, que pode ser adiada novamente.
 *
 * Registrado no AndroidManifest com exported="false" (só recebe
 * PendingIntens explícitos do próprio app).
 */
class BoletoReminderReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BoletoReminderReceiver"

        const val ACTION_SHOW_OPTIONS = "com.marinov.openfei.boletos.ACTION_SHOW_OPTIONS"
        const val ACTION_SNOOZE = "com.marinov.openfei.boletos.ACTION_SNOOZE"
        const val ACTION_REMIND = "com.marinov.openfei.boletos.ACTION_REMIND"

        const val EXTRA_HOURS = "extra_hours"

        /** Opções exibidas na notificação expandida (em horas). */
        val SNOOZE_OPTIONS = listOf(1, 2, 3, 6, 12)
    }

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        when (intent.action) {
            ACTION_SHOW_OPTIONS -> {
                Log.d(TAG, "Exibindo opções de lembrete na notificação")
                Notifications.sendBoletosSnoozeOptions(app)
            }

            ACTION_SNOOZE -> {
                val horas = intent.getIntExtra(EXTRA_HOURS, SNOOZE_OPTIONS.first())
                if (horas !in SNOOZE_OPTIONS) {
                    Log.w(TAG, "Valor de horas inválido: $horas")
                    return
                }
                Log.d(TAG, "Adiando lembrete de boleto em ${horas}h")
                Notifications.snoozeBoletos(app, horas)
            }

            ACTION_REMIND -> {
                Log.d(TAG, "Reexibindo notificação de boleto (lembrete/voltar)")
                Notifications.sendBoletos(app)
            }

            else -> Log.w(TAG, "Ação desconhecida: ${intent.action}")
        }
    }
}