package com.marinov.openfei.background

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context

/**
 * ★ REMOÇÃO DAS PERMISSÕES DE ALARME EXATO
 *
 * Ponto único de agendamento de alarmes do app (sync FEI/Moodle e
 * lembrete de boleto). Usa APENAS setAndAllowWhileIdle():
 *
 *  - NÃO exige SCHEDULE_EXACT_ALARM nem USE_EXACT_ALARM (Play Store
 *    restringe essas permissões a apps de alarme/calendário);
 *  - dispara mesmo com o aparelho em Doze (o sistema agrupa o disparo
 *    na próxima janela de manutenção — atraso típico de poucos minutos);
 *  - a cadência do app é garantida pela RE-ARMAGEM a cada disparo
 *    (SyncAlarmReceiver) + WorkManager periódico como rede de segurança.
 *
 * Para janelas de 1h+ (FEI 2h, Moodle >= 1h, boleto >= 1h) a diferença
 * entre exato e inexact é irrelevante na prática.
 */
internal object AlarmHelper {

    /** Agenda um wakeup inexact compatível com Doze, sem permissões especiais. */
    fun schedule(context: Context, triggerAtMillis: Long, pendingIntent: PendingIntent) {
        val am = context.applicationContext
            .getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
    }
}