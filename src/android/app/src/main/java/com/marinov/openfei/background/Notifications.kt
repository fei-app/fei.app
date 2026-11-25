package com.marinov.openfei.background

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import android.widget.RemoteViews
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.marinov.openfei.R
import com.marinov.openfei.data.Nota
import com.marinov.openfei.ui.main.MainActivity

/**
 * Centraliza o envio de notificações de sync, separando-as do ciclo de vida
 * do antigo BackgroundService (agora inexistente).
 *
 * ★ Lembrar boleto ("Lembrar mais tarde"):
 *  - sendBoletos() tem a ação "Lembrar mais tarde";
 *  - sendBoletosSnoozeOptions() reposta a MESMA notificação (ID_BOLETOS)
 *    com layout customizado expandido: 1h / 2h / 3h / 6h / 12h + Voltar;
 *  - snoozeBoletos() agenda o reenvio via AlarmHelper
 *    (★ inexact com setAndAllowWhileIdle — SEM permissões de alarme exato;
 *    o disparo pode atrasar alguns minutos em Doze, o que é aceitável
 *    para um lembrete de boleto);
 *  - o horário agendado é persistido para sobreviver a reboot
 *    (rearmBoletoReminderIfNeeded, chamado pelo BootReceiver).
 */
object Notifications {

    private const val TAG = "Notifications"

    private const val CH_NOTAS = "notas_channel"
    private const val CH_BOLETOS = "boletos_channel"
    private const val CH_HORARIO = "calendar_update_channel"

    private const val ID_NOTAS = 4001
    private const val ID_BOLETOS = 3001
    private const val ID_HORARIO = 2001

    // ★ Request codes exclusivos dos lembretes de boleto
    // (não colidem com 0/200/300 das notificações nem 8000/8500 do sync).
    private const val REQ_BOLETO_OPCOES = 3100
    private const val REQ_BOLETO_SNOOZE_BASE = 3110 // + horas (1,2,3,6,12)
    private const val REQ_BOLETO_LEMBRETE = 3190
    private const val REQ_BOLETO_VOLTAR = 3191

    // Persistência do lembrete (sobrevive a reboot)
    private const val PREFS_BOLETO_REMINDER = "boleto_reminder_prefs"
    private const val KEY_REMINDER_TRIGGER_AT = "reminder_trigger_at"

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(
                CH_NOTAS,
                context.getString(R.string.notif_canal_notas),
                NotificationManager.IMPORTANCE_DEFAULT
            )
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CH_BOLETOS,
                context.getString(R.string.notif_canal_boletos),
                NotificationManager.IMPORTANCE_DEFAULT
            )
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CH_HORARIO,
                context.getString(R.string.notif_canal_horario),
                NotificationManager.IMPORTANCE_DEFAULT
            )
        )
    }

    fun sendNotas(context: Context, notasAlteradas: List<Nota>) {
        if (!hasPermission(context)) return
        val text = buildString {
            notasAlteradas.forEach { n ->
                append("${n.nomeDisciplina} (${n.codigoDisciplina}) - ${n.tipoProva}: ${n.valor}\n")
            }
        }.trim()

        val pi = mainPendingIntent(context, 0, "notas")
        val notif = NotificationCompat.Builder(context, CH_NOTAS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notif_notas_titulo))
            .setContentText(context.getString(R.string.notif_notas_texto))
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        notifySafely(context, ID_NOTAS, notif)
    }

    fun sendBoletos(context: Context) {
        if (!hasPermission(context)) return

        // Um aviso NOVO de boleto substitui qualquer lembrete adiado pendente
        // (os dados acabaram de mudar; o lembrete antigo não faz mais sentido).
        cancelBoletoReminder(context)

        val pi = mainPendingIntent(context, 300, "boletos")
        val notif = NotificationCompat.Builder(context, CH_BOLETOS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notif_boletos_titulo))
            .setContentText(context.getString(R.string.notif_boletos_texto))
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(context.getString(R.string.notif_boletos_big_text))
            )
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pi)
            .setAutoCancel(true)
            // ★ Ação que "expande" a notificação com as opções de lembrete.
            .addAction(
                0,
                context.getString(R.string.notif_boletos_lembrar_mais_tarde),
                boletosOpcoesPendingIntent(context)
            )
            .build()
        notifySafely(context, ID_BOLETOS, notif)
    }

    /**
     * ★ Reposta a MESMA notificação de boleto (mesmo ID) em "modo expandido":
     * um RemoteViews com as opções 1h / 2h / 3h / 6h / 12h e um botão Voltar.
     *
     * O sistema limita a 3 os botões de ação visíveis numa notificação padrão,
     * por isso as 5 opções usam um layout customizado (DecoratedCustomViewStyle
     * mantém o cabeçalho padrão: ícone, nome do app e hora).
     */
    fun sendBoletosSnoozeOptions(context: Context) {
        if (!hasPermission(context)) return

        val remoteViews = RemoteViews(
            context.packageName,
            R.layout.notification_boleto_snooze
        ).apply {
            setTextViewText(
                R.id.tv_snooze_titulo,
                context.getString(R.string.notif_boletos_lembrar_titulo)
            )
            setTextViewText(
                R.id.tv_snooze_subtitulo,
                context.getString(R.string.notif_boletos_lembrar_texto)
            )
            setTextViewText(
                R.id.btn_snooze_voltar,
                context.getString(R.string.notif_boletos_voltar)
            )
            setOnClickPendingIntent(R.id.btn_snooze_voltar, boletosVoltarPendingIntent(context))
        }

        val botoes = mapOf(
            R.id.btn_snooze_1h to 1,
            R.id.btn_snooze_2h to 2,
            R.id.btn_snooze_3h to 3,
            R.id.btn_snooze_6h to 6,
            R.id.btn_snooze_12h to 12
        )
        botoes.forEach { (viewId, horas) ->
            remoteViews.setTextViewText(
                viewId,
                context.getString(R.string.notif_boletos_opcao_horas, horas)
            )
            remoteViews.setOnClickPendingIntent(viewId, boletosSnoozePendingIntent(context, horas))
        }

        val notif = NotificationCompat.Builder(context, CH_BOLETOS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notif_boletos_lembrar_titulo))
            .setContentText(context.getString(R.string.notif_boletos_lembrar_texto))
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setCustomBigContentView(remoteViews)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(mainPendingIntent(context, 300, "boletos"))
            .setAutoCancel(true)
            .setOnlyAlertOnce(true) // não repete som/vibração ao trocar o layout
            .build()
        notifySafely(context, ID_BOLETOS, notif)
    }

    /**
     * ★ Escolha de uma opção: cancela a notificação, agenda o reenvio
     * daqui a [horas] horas e dá feedback ao usuário via Toast.
     */
    fun snoozeBoletos(context: Context, horas: Int) {
        NotificationManagerCompat.from(context).cancel(ID_BOLETOS)
        val triggerAt = System.currentTimeMillis() + horas * 60L * 60L * 1000L
        reminderPrefs(context).edit { putLong(KEY_REMINDER_TRIGGER_AT, triggerAt) }
        scheduleBoletoReminderAt(context, triggerAt)
        Toast.makeText(
            context,
            context.getString(R.string.notif_boletos_lembrete_agendado, horas),
            Toast.LENGTH_SHORT
        ).show()
    }

    /**
     * ★ Chamado pelo BootReceiver: se havia um lembrete pendente antes do
     * reboot, re-arma o alarme (ou notifica imediatamente se já venceu).
     */
    fun rearmBoletoReminderIfNeeded(context: Context) {
        val triggerAt = reminderPrefs(context).getLong(KEY_REMINDER_TRIGGER_AT, 0L)
        if (triggerAt <= 0L) return
        if (triggerAt <= System.currentTimeMillis()) {
            Log.d(TAG, "Lembrete de boleto venceu durante o reboot — notificando agora")
            reminderPrefs(context).edit { remove(KEY_REMINDER_TRIGGER_AT) }
            sendBoletos(context)
        } else {
            Log.d(TAG, "Re-armando lembrete de boleto pós-reboot")
            scheduleBoletoReminderAt(context, triggerAt)
        }
    }

    fun sendHorario(context: Context) {
        if (!hasPermission(context)) return
        val pi = mainPendingIntent(context, 200, "horarios")
        val notif = NotificationCompat.Builder(context, CH_HORARIO)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notif_horario_titulo))
            .setContentText(context.getString(R.string.notif_horario_texto))
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(context.getString(R.string.notif_horario_big_text))
            )
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        notifySafely(context, ID_HORARIO, notif)
    }

    // ------------------------------------------------------------------
    // Alarme do lembrete de boleto
    // ------------------------------------------------------------------

    /**
     * ★ Inexact via AlarmHelper (setAndAllowWhileIdle): dispara mesmo em
     * Doze, sem SCHEDULE_EXACT_ALARM / USE_EXACT_ALARM. O sistema pode
     * atrasar o disparo em alguns minutos — aceitável para este lembrete.
     */
    private fun scheduleBoletoReminderAt(context: Context, triggerAtMillis: Long) {
        AlarmHelper.schedule(context, triggerAtMillis, boletosLembretePendingIntent(context))
    }

    private fun cancelBoletoReminder(context: Context) {
        reminderPrefs(context).edit { remove(KEY_REMINDER_TRIGGER_AT) }
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = PendingIntent.getBroadcast(
            context,
            REQ_BOLETO_LEMBRETE,
            Intent(context, BoletoReminderReceiver::class.java)
                .setAction(BoletoReminderReceiver.ACTION_REMIND),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        if (pi != null) {
            am.cancel(pi)
            pi.cancel()
        }
    }

    private fun reminderPrefs(context: Context) =
        context.applicationContext
            .getSharedPreferences(PREFS_BOLETO_REMINDER, Context.MODE_PRIVATE)

    // ------------------------------------------------------------------
    // PendingIntents do lembrete de boleto
    // ------------------------------------------------------------------

    private fun boletosOpcoesPendingIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            REQ_BOLETO_OPCOES,
            Intent(context, BoletoReminderReceiver::class.java)
                .setAction(BoletoReminderReceiver.ACTION_SHOW_OPTIONS),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun boletosSnoozePendingIntent(context: Context, horas: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            REQ_BOLETO_SNOOZE_BASE + horas,
            Intent(context, BoletoReminderReceiver::class.java)
                .setAction(BoletoReminderReceiver.ACTION_SNOOZE)
                .putExtra(BoletoReminderReceiver.EXTRA_HOURS, horas),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun boletosLembretePendingIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            REQ_BOLETO_LEMBRETE,
            Intent(context, BoletoReminderReceiver::class.java)
                .setAction(BoletoReminderReceiver.ACTION_REMIND),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun boletosVoltarPendingIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            REQ_BOLETO_VOLTAR,
            Intent(context, BoletoReminderReceiver::class.java)
                .setAction(BoletoReminderReceiver.ACTION_REMIND),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    // ------------------------------------------------------------------
    // Comuns
    // ------------------------------------------------------------------

    private fun mainPendingIntent(context: Context, requestCode: Int, destination: String) =
        PendingIntent.getActivity(
            context,
            requestCode,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                putExtra("destination", destination)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun hasPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(
                    context, Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED

    private fun notifySafely(context: Context, id: Int, notif: android.app.Notification) {
        try {
            NotificationManagerCompat.from(context).notify(id, notif)
        } catch (_: SecurityException) {
            // permissão revogada entre o check e o envio
        }
    }
}