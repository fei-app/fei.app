package com.marinov.openfei.background

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
import com.marinov.openfei.data.BoletosRepository
import com.marinov.openfei.data.CalendarioRepository
import com.marinov.openfei.data.DataInitializer
import com.marinov.openfei.data.HorariosRepository
import com.marinov.openfei.data.Nota
import com.marinov.openfei.data.NotasRepository
import com.marinov.openfei.data.SessionExpiredException
import com.marinov.openfei.network.NetworkChecker
import com.marinov.openfei.ui.main.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.TimeZone

class BackgroundService : Service() {

    companion object {
        private const val TAG = "BackgroundService"
        private const val FOREGROUND_CHANNEL_ID = "background_service_channel"
        const val FOREGROUND_NOTIFICATION_ID = 9001
        private const val SYNC_CHANNEL_ID = "sync_updates_channel"
        private const val SYNC_ID_FEI = 9011
        private const val SYNC_ID_MOODLE = 9015
        private const val SYNC_ID_BOOT = 9016
        private const val NOTIF_ID_NOTAS = 4001
        private const val NOTIF_ID_BOLETOS = 3001
        private const val NOTIF_ID_HORARIO = 2001
        private const val EXTRA_SYNC_ON_START = "sync_on_start"

        /**
         * Todos os agendamentos usam SEMPRE o horário de Brasília,
         * independente do fuso horário configurado no dispositivo.
         */
        private const val BRASALIA_TZ = "America/Sao_Paulo"

        /**
         * Janela fixa FEI (notas, provas FEI, boletos, horários):
         * 07:10 – 09:10 – 11:10 – 13:10 – 15:10 – 17:10 – 19:10 – 21:10 – 23:10,
         * de segunda a sábado.
         */
        private val FEI_HOURS = intArrayOf(7, 9, 11, 13, 15, 17, 19, 21, 23)
        private const val FEI_MINUTE = 10

        /**
         * Janela Moodle: entre 7h e 23h, com intervalo configurável
         * (1h por padrão). Fora dessa janela não atualiza.
         */
        private const val MOODLE_START_HOUR = 7
        private const val MOODLE_END_HOUR = 23

        fun start(context: Context, syncOnStart: Boolean = false) {
            val intent = Intent(context, BackgroundService::class.java).apply {
                putExtra(EXTRA_SYNC_ON_START, syncOnStart)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
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
        // Inicializa cache, sessões, repositórios, SyncPrefs e WebView de uma vez
        DataInitializer.init(applicationContext)
        startPeriodicTasks()
        Log.d(TAG, "Serviço em segundo plano iniciado.")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand | flags=$flags | startId=$startId")
        val syncOnStart = intent?.getBooleanExtra(EXTRA_SYNC_ON_START, false) ?: false
        if (syncOnStart) {
            performImmediateSync()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        Log.d(TAG, "Serviço em segundo plano encerrado.")
    }

    // -------------------------------------------------------------------------
    // Sincronização imediata (boot)
    // -------------------------------------------------------------------------

    /**
     * Sincroniza todos os dados imediatamente, independente do horário de
     * agendamento. Usada no boot do dispositivo. Respeita a regra de
     * rede limitada/ilimitada.
     */
    private fun performImmediateSync() {
        Log.d(TAG, "Sincronização imediata (boot) solicitada")
        serviceScope.launch {
            if (!isSyncAllowedByNetwork()) {
                Log.d(TAG, "Sincronização imediata ignorada (rede limitada)")
                return@launch
            }
            val isResponsavel = AppMode.isResponsavelFinanceiro
            runWithSyncNotification(SYNC_ID_BOOT) {
                if (!isResponsavel) {
                    runNotasLogic()
                    runHorarioLogic()
                    runProvasFeiLogic()
                    runEventosMoodleLogic()
                }
                runBoletosLogic()
                if (!isResponsavel) {
                    syncSystemCalendar()
                }
            }
            Log.d(TAG, "Sincronização imediata (boot) concluída")
        }
    }

    // -------------------------------------------------------------------------
    // Agendamento
    // -------------------------------------------------------------------------

    private fun startPeriodicTasks() {
        Log.d(TAG, "startPeriodicTasks iniciado")

        // FEI: notas, provas FEI, boletos e horários em horários fixos (Brasília)
        serviceScope.launch {
            while (isActive) {
                val next = nextFeiScheduleTime()
                val delayMs = (next - System.currentTimeMillis()).coerceAtLeast(0L)
                Log.d(TAG, "FEI: próxima sincronização em ${delayMs}ms")
                delay(delayMs)
                if (!isActive) break
                if (!isSyncAllowedByNetwork()) {
                    Log.d(TAG, "FEI: sincronização ignorada (rede limitada)")
                    continue
                }
                runWithSyncNotification(SYNC_ID_FEI) {
                    runFeiSync()
                }
            }
        }

        // Moodle: eventos do calendário em intervalo configurável (7h às 23h, Brasília)
        serviceScope.launch {
            while (isActive) {
                val next = nextMoodleScheduleTime()
                val delayMs = (next - System.currentTimeMillis()).coerceAtLeast(0L)
                Log.d(TAG, "Moodle: próxima sincronização em ${delayMs}ms")
                delay(delayMs)
                if (!isActive) break
                if (AppMode.isResponsavelFinanceiro) {
                    Log.d(TAG, "Moodle: ignorado (modo responsável financeiro ativo)")
                    continue
                }
                if (!isSyncAllowedByNetwork()) {
                    Log.d(TAG, "Moodle: sincronização ignorada (rede limitada)")
                    continue
                }
                runWithSyncNotification(SYNC_ID_MOODLE) {
                    runMoodleSync()
                }
            }
        }
    }

    /**
     * Verifica a preferência "Sincronizar apenas em redes ilimitadas".
     */
    private fun isSyncAllowedByNetwork(): Boolean {
        if (!SyncPrefs.syncOnlyOnUnmeteredNetworks) return true
        val metered = NetworkChecker.isNetworkMetered(applicationContext)
        return !metered
    }

    /**
     * Próximo horário da janela FEI: 07:10, 09:10, ..., 23:10,
     * de segunda a sábado, SEMPRE no horário de Brasília.
     * Usa java.util.Calendar para compatibilidade com Android 7 (API 24).
     */
    private fun nextFeiScheduleTime(): Long {
        val tz = TimeZone.getTimeZone(BRASALIA_TZ)
        val now = Calendar.getInstance(tz)
        if (now.get(Calendar.DAY_OF_WEEK) != Calendar.SUNDAY) {
            for (h in FEI_HOURS) {
                val candidate = Calendar.getInstance(tz).apply {
                    timeInMillis = now.timeInMillis
                    set(Calendar.HOUR_OF_DAY, h)
                    set(Calendar.MINUTE, FEI_MINUTE)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                if (candidate.after(now)) {
                    return candidate.timeInMillis
                }
            }
        }
        val nextDay = Calendar.getInstance(tz).apply {
            timeInMillis = now.timeInMillis
            add(Calendar.DAY_OF_YEAR, 1)
            while (get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY) {
                add(Calendar.DAY_OF_YEAR, 1)
            }
            set(Calendar.HOUR_OF_DAY, FEI_HOURS[0])
            set(Calendar.MINUTE, FEI_MINUTE)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return nextDay.timeInMillis
    }

    /**
     * Próximo horário da janela Moodle: a cada N horas (configurável),
     * entre 7h e 23h, todos os dias, no horário de Brasília.
     * Usa java.util.Calendar para compatibilidade com Android 7 (API 24).
     */
    private fun nextMoodleScheduleTime(): Long {
        val tz = TimeZone.getTimeZone(BRASALIA_TZ)
        val now = Calendar.getInstance(tz)
        val freqHours = SyncPrefs.moodleFrequencyHours
        var hour = MOODLE_START_HOUR
        while (hour <= MOODLE_END_HOUR) {
            val candidate = Calendar.getInstance(tz).apply {
                timeInMillis = now.timeInMillis
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            if (candidate.after(now)) {
                return candidate.timeInMillis
            }
            hour += freqHours
        }
        val tomorrow = Calendar.getInstance(tz).apply {
            timeInMillis = now.timeInMillis
            add(Calendar.DAY_OF_YEAR, 1)
            set(Calendar.HOUR_OF_DAY, MOODLE_START_HOUR)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return tomorrow.timeInMillis
    }

    // -------------------------------------------------------------------------
    // Sincronizações
    // -------------------------------------------------------------------------

    /**
     * Sincronização do portal FEI: notas, horários, provas FEI e boletos.
     * Não inclui nada do Moodle.
     */
    private suspend fun runFeiSync() {
        val isResponsavel = AppMode.isResponsavelFinanceiro
        if (!isResponsavel) {
            runNotasLogic()
            runHorarioLogic()
            runProvasFeiLogic()
        }
        runBoletosLogic()
        if (!isResponsavel) {
            syncSystemCalendar()
        }
    }

    /**
     * Sincronização somente do Moodle: eventos do calendário.
     */
    private suspend fun runMoodleSync() {
        runEventosMoodleLogic()
        syncSystemCalendar()
    }

    private suspend fun runNotasLogic() {
        try {
            val notasAlteradas = NotasRepository.atualizarNotas(online = true)
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
            val houve = BoletosRepository.atualizaBoletos()
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
            val horarioAlterado = HorariosRepository.novoHorario(online = true)
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

    private suspend fun runProvasFeiLogic() {
        try {
            val provas = CalendarioRepository.obterProvasFEIOnlineOrNull()
            Log.d(TAG, "ProvasFeiLogic: ${provas?.size ?: 0} provas FEI atualizadas.")
        } catch (e: Exception) {
            Log.e(TAG, "ProvasFeiLogic: erro", e)
        }
    }

    private suspend fun runEventosMoodleLogic() {
        try {
            val eventos = CalendarioRepository.obterEventosMoodleOnlineOrNull()
            Log.d(TAG, "EventosMoodleLogic: ${eventos?.size ?: 0} eventos Moodle atualizados.")
        } catch (e: Exception) {
            Log.e(TAG, "EventosMoodleLogic: erro", e)
        }
    }

    private suspend fun syncSystemCalendar() {
        try {
            CalendarSyncManager.syncCached(
                context = applicationContext,
                force = true,
                allowDeleteMissing = false
            )
            Log.d(TAG, "Calendário do sistema sincronizado.")
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao sincronizar calendário do sistema", e)
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

    // -------------------------------------------------------------------------
    // Notificações
    // -------------------------------------------------------------------------

    private fun createNotificationChannels() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
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
            nm.deleteNotificationChannel("update_channel")
        }
    }

    private fun buildForegroundNotification() =
        NotificationCompat.Builder(this, FOREGROUND_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notif_servico_titulo))
            .setContentText(getString(R.string.notif_servico_texto))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

    private fun showSyncNotification(notificationId: Int) {
        if (!hasNotificationPermission()) return
        val notification = NotificationCompat.Builder(this, SYNC_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_sync)
            .setContentTitle(getString(R.string.notif_sync_titulo))
            .setContentText(getString(R.string.notif_sync_texto))
            .setPriority(NotificationCompat.PRIORITY_MIN)
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
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED

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
}