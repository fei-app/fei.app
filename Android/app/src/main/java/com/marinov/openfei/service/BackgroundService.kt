package com.marinov.openfei.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.marinov.openfei.R
import com.marinov.openfei.app.AppMode
import com.marinov.openfei.data.CalendarioRepository
import com.marinov.openfei.data.CalendarSyncManager
import com.marinov.openfei.data.Dados
import com.marinov.openfei.data.Nota
import com.marinov.openfei.data.SessionExpiredException
import com.marinov.openfei.data.UpdateChecker
import com.marinov.openfei.ui.main.MainActivity
import com.marinov.openfei.ui.settings.SettingsActivity
import com.marinov.openfei.util.WebViewHelper
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

class BackgroundService : Service() {

    companion object {
        private const val TAG = "BackgroundService"

        private const val FOREGROUND_CHANNEL_ID = "background_service_channel"
        const val FOREGROUND_NOTIFICATION_ID = 9001

        private const val SYNC_CHANNEL_ID = "sync_updates_channel"

        private const val SYNC_ID_NOTAS = 9011
        private const val SYNC_ID_BOLETOS = 9012
        private const val SYNC_ID_HORARIO = 9013
        private const val SYNC_ID_UPDATE = 9014
        private const val SYNC_ID_CALENDARIO = 9015

        private const val NOTIF_ID_NOTAS = 4001
        private const val NOTIF_ID_BOLETOS = 3001
        private const val NOTIF_ID_HORARIO = 2001
        private const val NOTIF_ID_UPDATE = 1001

        private const val NOTAS_INTERVAL_MS = 20L * 60 * 1000
        private const val BOLETOS_INTERVAL_MS = 20L * 60 * 1000
        private const val HORARIO_INTERVAL_MS = 20L * 60 * 1000
        private const val UPDATE_INTERVAL_MS = 120L * 60 * 1000
        private const val CALENDARIO_INTERVAL_MS = 20L * 60 * 1000

        fun start(context: Context) {
            val intent = Intent(context, BackgroundService::class.java)
            context.startForegroundService(intent)
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()

        Log.d(TAG, "onCreate do BackgroundService")

        createNotificationChannels()

        try {
            startForeground(FOREGROUND_NOTIFICATION_ID, buildForegroundNotification())
        } catch (e: Exception) {
            Log.w(TAG, "Erro ao chamar startForeground()", e)
        }

        Dados.init(applicationContext)
        WebViewHelper.ensureWebView(applicationContext)

        startPeriodicTasks()

        Log.d(TAG, "Serviço em segundo plano iniciado.")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand | flags=$flags | startId=$startId")
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        Log.d(TAG, "Serviço em segundo plano encerrado.")
    }

    private fun startPeriodicTasks() {
        Log.d(TAG, "startPeriodicTasks iniciado")

        serviceScope.launch {
            while (isActive) {
                delay(NOTAS_INTERVAL_MS.milliseconds)

                if (!AppMode.isResponsavelFinanceiro) {
                    runWithSyncNotification(SYNC_ID_NOTAS) {
                        runNotasLogic()
                    }
                }
            }
        }

        serviceScope.launch {
            while (isActive) {
                delay(BOLETOS_INTERVAL_MS.milliseconds)

                runWithSyncNotification(SYNC_ID_BOLETOS) {
                    runBoletosLogic()
                }
            }
        }

        serviceScope.launch {
            while (isActive) {
                delay(HORARIO_INTERVAL_MS.milliseconds)

                if (!AppMode.isResponsavelFinanceiro) {
                    runWithSyncNotification(SYNC_ID_HORARIO) {
                        runHorarioLogic()
                    }
                }
            }
        }

        serviceScope.launch {
            Log.d(TAG, "Tarefa de calendário iniciada. Intervalo=${CALENDARIO_INTERVAL_MS}ms")

            // Execução imediata ao subir o serviço (importante no boot receiver)
            if (!AppMode.isResponsavelFinanceiro) {
                Log.d(TAG, "Calendário: execução imediata no start do serviço")

                runWithSyncNotification(SYNC_ID_CALENDARIO) {
                    runCalendarioLogic()
                }
            } else {
                Log.d(TAG, "Calendário ignorado: modo responsável financeiro ativo")
            }

            while (isActive) {
                delay(CALENDARIO_INTERVAL_MS.milliseconds)

                if (!AppMode.isResponsavelFinanceiro) {
                    Log.d(TAG, "Calendário: execução periódica")

                    runWithSyncNotification(SYNC_ID_CALENDARIO) {
                        runCalendarioLogic()
                    }
                }
            }
        }

        serviceScope.launch {
            while (isActive) {
                delay(UPDATE_INTERVAL_MS.milliseconds)

                runWithSyncNotification(SYNC_ID_UPDATE) {
                    runUpdateCheckLogic()
                }
            }
        }
    }

    private suspend fun runWithSyncNotification(
        notificationId: Int,
        block: suspend () -> Unit
    ) {
        showSyncNotification(notificationId)

        try {
            block()
        } finally {
            cancelNotification(notificationId)
        }
    }

    private suspend fun runNotasLogic() {
        try {
            val notasAlteradas = Dados.atualizarNotas(online = true)

            if (notasAlteradas.isNotEmpty()) {
                Log.d(TAG, "NotasLogic: ${notasAlteradas.size} nota(s) alterada(s).")
                sendNotasNotification(notasAlteradas)
            } else {
                Log.d(TAG, "NotasLogic: nenhuma nota alterada.")
            }
        } catch (_: SessionExpiredException) {
            Log.w(TAG, "NotasLogic: sessão expirada — login automático falhou silenciosamente.")
        } catch (e: Exception) {
            Log.e(TAG, "NotasLogic: erro", e)
        }
    }

    private suspend fun runBoletosLogic() {
        try {
            val houve = Dados.atualizaBoletos()

            if (houve) {
                Log.d(TAG, "BoletosLogic: alteração detectada.")
                sendBoletosNotification()
            } else {
                Log.d(TAG, "BoletosLogic: nenhuma alteração.")
            }
        } catch (_: SessionExpiredException) {
            Log.w(TAG, "BoletosLogic: sessão expirada — login automático falhou silenciosamente.")
        } catch (e: Exception) {
            Log.e(TAG, "BoletosLogic: erro", e)
        }
    }

    private suspend fun runHorarioLogic() {
        try {
            val horarioAlterado = Dados.novoHorario(online = true)

            if (horarioAlterado) {
                Log.d(TAG, "HorarioLogic: alteração detectada.")
                sendHorarioNotification()
            } else {
                Log.d(TAG, "HorarioLogic: nenhuma alteração.")
            }
        } catch (_: SessionExpiredException) {
            Log.w(TAG, "HorarioLogic: sessão expirada — login automático falhou silenciosamente.")
        } catch (e: Exception) {
            Log.e(TAG, "HorarioLogic: erro", e)
        }
    }

    private suspend fun runCalendarioLogic() {
        Log.d(TAG, "CalendarioLogic: iniciando")

        val provasFeiOnline = try {
            CalendarioRepository.obterProvasFEIOnlineOrNull()
        } catch (e: Exception) {
            Log.e(TAG, "CalendarioLogic: erro inesperado ao tentar obter provas FEI online", e)
            null
        }

        val feiOk = provasFeiOnline != null
        val provasFei = provasFeiOnline ?: CalendarioRepository.obterProvasFEICache()

        Log.d(
            TAG,
            "CalendarioLogic: provas FEI | onlineOk=$feiOk | size=${provasFei.size}"
        )

        val eventosMoodleOnline = try {
            CalendarioRepository.obterEventosMoodleOnlineOrNull()
        } catch (e: Exception) {
            Log.e(TAG, "CalendarioLogic: erro inesperado ao tentar obter eventos Moodle online", e)
            null
        }

        val moodleOk = eventosMoodleOnline != null
        val eventosMoodle = eventosMoodleOnline ?: CalendarioRepository.obterEventosMoodleCache()

        Log.d(
            TAG,
            "CalendarioLogic: eventos Moodle | onlineOk=$moodleOk | size=${eventosMoodle.size}"
        )

        val allowDeleteMissing = feiOk && moodleOk

        Log.d(
            TAG,
            "CalendarioLogic: total para sync=${provasFei.size + eventosMoodle.size} | allowDeleteMissing=$allowDeleteMissing"
        )

        try {
            CalendarSyncManager.syncCached(
                context = applicationContext,
                force = true,
                allowDeleteMissing = allowDeleteMissing
            )

            Log.d(TAG, "CalendarioLogic: syncCached concluído")
        } catch (e: Exception) {
            Log.e(TAG, "CalendarioLogic: erro ao sincronizar com a agenda", e)
        }

        Log.d(TAG, "CalendarioLogic: finalizado")
    }

    private suspend fun runUpdateCheckLogic() {
        try {
            val deferred = CompletableDeferred<Unit>()

            UpdateChecker.checkForUpdate(
                applicationContext,
                isManualCheck = false,
                listener = object : UpdateChecker.UpdateListener {
                    override fun onUpdateAvailable(
                        url: String,
                        version: String,
                        releaseNotes: String
                    ) {
                        sendUpdateNotification(version)
                        deferred.complete(Unit)
                    }

                    override fun onUpToDate() {
                        deferred.complete(Unit)
                    }

                    override fun onError(message: String) {
                        Log.e(TAG, "UpdateLogic: erro → $message")
                        deferred.complete(Unit)
                    }
                }
            )

            deferred.await()
        } catch (e: Exception) {
            Log.e(TAG, "UpdateLogic: exceção", e)
        }
    }

    private fun createNotificationChannels() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

        nm.createNotificationChannel(
            NotificationChannel(
                FOREGROUND_CHANNEL_ID,
                getString(R.string.notif_canal_servico),
                NotificationManager.IMPORTANCE_LOW
            )
        )

        nm.createNotificationChannel(
            NotificationChannel(
                SYNC_CHANNEL_ID,
                getString(R.string.notif_canal_sync),
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                setSound(null, null)
                enableVibration(false)
            }
        )

        nm.createNotificationChannel(
            NotificationChannel(
                "notas_channel",
                getString(R.string.notif_canal_notas),
                NotificationManager.IMPORTANCE_DEFAULT
            )
        )

        nm.createNotificationChannel(
            NotificationChannel(
                "boletos_channel",
                getString(R.string.notif_canal_boletos),
                NotificationManager.IMPORTANCE_DEFAULT
            )
        )

        nm.createNotificationChannel(
            NotificationChannel(
                "calendar_update_channel",
                getString(R.string.notif_canal_horario),
                NotificationManager.IMPORTANCE_DEFAULT
            )
        )

        nm.createNotificationChannel(
            NotificationChannel(
                "update_channel",
                getString(R.string.notif_canal_update),
                NotificationManager.IMPORTANCE_HIGH
            )
        )
    }

    private fun buildForegroundNotification() =
        NotificationCompat.Builder(this, FOREGROUND_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notif_servico_titulo))
            .setContentText(getString(R.string.notif_servico_texto))
            .setOngoing(true)
            .setSilent(true)
            .build()

    private fun showSyncNotification(notificationId: Int) {
        if (!hasNotificationPermission()) return

        val notification = NotificationCompat.Builder(this, SYNC_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_service)
            .setContentTitle(getString(R.string.notif_sync_titulo))
            .setContentText(getString(R.string.notif_sync_texto))
            .setSilent(true)
            .setOngoing(true)
            .build()

        try {
            NotificationManagerCompat.from(this).notify(notificationId, notification)
        } catch (e: SecurityException) {
            Log.w(TAG, "Sem permissão para notificação de sync #$notificationId", e)
        }
    }

    private fun cancelNotification(notificationId: Int) {
        try {
            NotificationManagerCompat.from(this).cancel(notificationId)
        } catch (e: Exception) {
            Log.w(TAG, "Erro ao cancelar notificação #$notificationId", e)
        }
    }

    private fun hasNotificationPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

    private fun sendNotasNotification(notasAlteradas: List<Nota>) {
        if (!hasNotificationPermission()) return

        val text = buildString {
            notasAlteradas.forEach { n ->
                append("${n.nomeDisciplina} (${n.codigoDisciplina}) - ${n.tipoProva}: ${n.valor}\n")
            }
        }.trim()

        val pi = PendingIntent.getActivity(
            applicationContext,
            0,
            Intent(applicationContext, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                putExtra("destination", "notas")
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(applicationContext, "notas_channel")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notif_notas_titulo))
            .setContentText(getString(R.string.notif_notas_texto))
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()

        try {
            NotificationManagerCompat.from(applicationContext).notify(NOTIF_ID_NOTAS, notification)
        } catch (e: SecurityException) {
            Log.w(TAG, "Sem permissão para notificação de notas", e)
        }
    }

    private fun sendBoletosNotification() {
        if (!hasNotificationPermission()) return

        val pi = PendingIntent.getActivity(
            applicationContext,
            300,
            Intent(applicationContext, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                putExtra("destination", "boletos")
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(applicationContext, "boletos_channel")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notif_boletos_titulo))
            .setContentText(getString(R.string.notif_boletos_texto))
            .setStyle(NotificationCompat.BigTextStyle().bigText(getString(R.string.notif_boletos_big_text)))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()

        try {
            NotificationManagerCompat.from(applicationContext).notify(NOTIF_ID_BOLETOS, notification)
        } catch (e: SecurityException) {
            Log.w(TAG, "Sem permissão para notificação de boletos", e)
        }
    }

    private fun sendHorarioNotification() {
        if (!hasNotificationPermission()) return

        val pi = PendingIntent.getActivity(
            applicationContext,
            200,
            Intent(applicationContext, MainActivity::class.java).apply {
                action = "com.marinov.openfei.ACTION_OPEN_HORARIOS"
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                putExtra("destination", "horarios")
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(applicationContext, "calendar_update_channel")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notif_horario_titulo))
            .setContentText(getString(R.string.notif_horario_texto))
            .setStyle(NotificationCompat.BigTextStyle().bigText(getString(R.string.notif_horario_big_text)))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()

        try {
            NotificationManagerCompat.from(applicationContext).notify(NOTIF_ID_HORARIO, notification)
        } catch (e: SecurityException) {
            Log.w(TAG, "Sem permissão para notificação de horário", e)
        }
    }

    private fun sendUpdateNotification(version: String) {
        if (!hasNotificationPermission()) return

        val pi = PendingIntent.getActivity(
            applicationContext,
            0,
            Intent(applicationContext, SettingsActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                putExtra("open_update_directly", true)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(applicationContext, "update_channel")
            .setSmallIcon(R.drawable.ic_update)
            .setContentTitle(getString(R.string.notif_update_titulo, version))
            .setContentText(getString(R.string.notif_update_texto))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()

        try {
            NotificationManagerCompat.from(applicationContext).notify(NOTIF_ID_UPDATE, notification)
        } catch (e: SecurityException) {
            Log.w(TAG, "Sem permissão para notificação de atualização", e)
        }
    }
}