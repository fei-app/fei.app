package com.marinov.openfei.background

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.marinov.openfei.R
import com.marinov.openfei.data.Nota
import com.marinov.openfei.ui.main.MainActivity

/**
 * Centraliza o envio de notificações de sync, separando-as do ciclo de vida
 * do antigo BackgroundService (agora inexistente).
 */
object Notifications {

    private const val CH_NOTAS = "notas_channel"
    private const val CH_BOLETOS = "boletos_channel"
    private const val CH_HORARIO = "calendar_update_channel"

    private const val ID_NOTAS = 4001
    private const val ID_BOLETOS = 3001
    private const val ID_HORARIO = 2001

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
            .build()
        notifySafely(context, ID_BOLETOS, notif)
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