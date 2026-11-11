package com.marinov.openfei.background

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.marinov.openfei.app.AppMode
import com.marinov.openfei.network.NetworkChecker
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicLong

/**
 * ★ ARQUITETURA CORRIGIDA
 *
 * - 1 alarme FEI (próxima janela) + 1 alarme Moodle. Não mais 9 alarmes.
 * - Cada disparo RE-ARMA a próxima janela (bug C).
 * - Alarmes exatos quando permitido; fallback inexact caso contrário (bug A).
 * - WorkManager periódico é apenas rede de segurança.
 */
object SyncScheduler {

    private const val TAG = "SyncScheduler"
    private const val BRASALIA_TZ = "America/Sao_Paulo"

    private val FEI_HOURS = intArrayOf(7, 9, 11, 13, 15, 17, 19, 21, 23)
    private const val FEI_MINUTE = 10
    private const val MOODLE_START_HOUR = 7
    private const val MOODLE_END_HOUR = 23

    /** ★ Um único request code por tipo — elimina os duplicados (bug B). */
    private const val REQ_FEI = 8000
    private const val REQ_MOODLE = 8500

    /** Request codes da versão antiga (8000..8008) — limpos uma vez. */
    private const val LEGACY_REQ_BASE = 8000
    private const val LEGACY_REQ_COUNT = 9
    private const val PREFS = "sync_scheduler_prefs"
    private const val KEY_LEGACY_CLEANED = "legacy_alarms_cleaned_v2"

    const val EXTRA_SYNC_TYPE = "sync_type"
    const val TYPE_FEI = "fei"
    const val TYPE_MOODLE = "moodle"
    const val TYPE_BOOT = "boot"

    private const val MIN_RESCHEDULE_INTERVAL_MS = 30_000L
    private val lastScheduleAt = AtomicLong(0L)

    // ------------------------------------------------------------------
    // API pública
    // ------------------------------------------------------------------

    fun scheduleAll(context: Context, force: Boolean = false) {
        val app = context.applicationContext
        val now = System.currentTimeMillis()
        val last = lastScheduleAt.get()
        if (!force && now - last < MIN_RESCHEDULE_INTERVAL_MS) return
        if (!lastScheduleAt.compareAndSet(last, now)) return

        cleanLegacyAlarmsOnce(app)
        scheduleNextFei(app)
        scheduleNextMoodle(app)
        PeriodicSyncWorker.schedulePeriodicBackup(app, replace = force)
        Log.d(TAG, "Agendamentos verificados | ${debugDescribe()}")
    }

    /** ★ BUG C: chamado pelo receiver DEPOIS de cada disparo. */
    fun scheduleNextFei(context: Context) {
        val app = context.applicationContext
        val at = nextFeiTime() ?: return
        setAlarm(app, REQ_FEI, TYPE_FEI, at)
    }

    fun scheduleNextMoodle(context: Context) {
        val app = context.applicationContext
        if (AppMode.isResponsavelFinanceiro) {
            cancelAlarm(app, REQ_MOODLE)
            PeriodicSyncWorker.cancelPeriodicBackup(app)
            return
        }
        val at = nextMoodleTime() ?: return
        setAlarm(app, REQ_MOODLE, TYPE_MOODLE, at)
    }

    fun rescheduleMoodle(context: Context) {
        val app = context.applicationContext
        scheduleNextMoodle(app)
        PeriodicSyncWorker.schedulePeriodicBackup(app, replace = true)
    }

    fun syncNow(context: Context, type: String = TYPE_BOOT) {
        PeriodicSyncWorker.enqueueOnce(context.applicationContext, type)
    }

    fun onBoot(context: Context) {
        val app = context.applicationContext
        if (isSyncAllowedByNetwork(app)) syncNow(app, TYPE_BOOT)
        scheduleAll(app, force = true)
    }

    /** true se o sistema permite alarme exato (USE_EXACT_ALARM ou appop). */
    fun canUseExactAlarms(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        return runCatching { am.canScheduleExactAlarms() }.getOrDefault(false)
    }

    // ------------------------------------------------------------------
    // Cálculo das janelas (Brasília)
    // ------------------------------------------------------------------

    /** ★ BUG B: retorna APENAS a próxima janela, não uma por hora. */
    private fun nextFeiTime(): Long? {
        val tz = TimeZone.getTimeZone(BRASALIA_TZ)
        val now = Calendar.getInstance(tz)

        if (now.get(Calendar.DAY_OF_WEEK) != Calendar.SUNDAY) {
            for (h in FEI_HOURS) {
                val c = (now.clone() as Calendar).apply {
                    set(Calendar.HOUR_OF_DAY, h)
                    set(Calendar.MINUTE, FEI_MINUTE)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                if (c.after(now)) return c.timeInMillis
            }
        }
        return (now.clone() as Calendar).apply {
            add(Calendar.DAY_OF_YEAR, 1)
            while (get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY) add(Calendar.DAY_OF_YEAR, 1)
            set(Calendar.HOUR_OF_DAY, FEI_HOURS[0])
            set(Calendar.MINUTE, FEI_MINUTE)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    private fun nextMoodleTime(): Long? {
        if (AppMode.isResponsavelFinanceiro) return null
        val tz = TimeZone.getTimeZone(BRASALIA_TZ)
        val now = Calendar.getInstance(tz)
        val freq = SyncPrefs.moodleFrequencyHours

        var hour = MOODLE_START_HOUR
        while (hour <= MOODLE_END_HOUR) {
            val c = (now.clone() as Calendar).apply {
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            if (c.after(now)) return c.timeInMillis
            hour += freq
        }
        return (now.clone() as Calendar).apply {
            add(Calendar.DAY_OF_YEAR, 1)
            set(Calendar.HOUR_OF_DAY, MOODLE_START_HOUR)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    /** Usado pelo worker para não sincronizar Moodle de madrugada. */
    fun isInsideMoodleWindow(): Boolean {
        val h = Calendar.getInstance(TimeZone.getTimeZone(BRASALIA_TZ))
            .get(Calendar.HOUR_OF_DAY)
        return h in MOODLE_START_HOUR..MOODLE_END_HOUR
    }

    // ------------------------------------------------------------------
    // AlarmManager
    // ------------------------------------------------------------------

    private fun setAlarm(app: Context, requestCode: Int, type: String, atMillis: Long) {
        val am = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = pendingIntent(app, requestCode, type)
        try {
            when {
                Build.VERSION.SDK_INT < Build.VERSION_CODES.M ->
                    am.setExact(AlarmManager.RTC_WAKEUP, atMillis, pi)

                canUseExactAlarms(app) ->
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pi)

                else -> {
                    Log.w(TAG, "Alarme exato indisponível — usando inexact para $type")
                    am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pi)
                }
            }
            Log.d(TAG, "Alarme $type -> ${Date(atMillis)} (exato=${canUseExactAlarms(app)})")
        } catch (e: SecurityException) {
            Log.w(TAG, "SecurityException no alarme exato; fallback inexact", e)
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pi)
        }
    }

    private fun pendingIntent(app: Context, requestCode: Int, type: String): PendingIntent =
        PendingIntent.getBroadcast(
            app, requestCode,
            Intent(app, SyncAlarmReceiver::class.java).putExtra(EXTRA_SYNC_TYPE, type),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun cancelAlarm(app: Context, requestCode: Int) {
        val am = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = PendingIntent.getBroadcast(
            app, requestCode,
            Intent(app, SyncAlarmReceiver::class.java),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        if (pi != null) {
            am.cancel(pi)
            pi.cancel()
        }
    }

    /** ★ BUG B: remove os 9 alarmes da versão antiga (8000..8008). */
    private fun cleanLegacyAlarmsOnce(app: Context) {
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_LEGACY_CLEANED, false)) return
        for (i in 0 until LEGACY_REQ_COUNT) cancelAlarm(app, LEGACY_REQ_BASE + i)
        prefs.edit().putBoolean(KEY_LEGACY_CLEANED, true).apply()
        Log.d(TAG, "Alarmes legados (8000..8008) removidos")
    }

    private fun isSyncAllowedByNetwork(app: Context): Boolean {
        if (!SyncPrefs.syncOnlyOnUnmeteredNetworks) return true
        return !NetworkChecker.isNetworkMetered(app)
    }

    // ------------------------------------------------------------------
    // Diagnóstico
    // ------------------------------------------------------------------

    fun debugDescribe(): String {
        val tz = TimeZone.getTimeZone(BRASALIA_TZ)
        val fmt = SimpleDateFormat("EEE dd/MM HH:mm", Locale.forLanguageTag("pt-BR"))
            .apply { timeZone = tz }
        val fei = nextFeiTime()
        val moodle = nextMoodleTime()
        return buildString {
            append("agora=").append(fmt.format(Date()))
            append(" | próxima FEI=").append(fei?.let { fmt.format(Date(it)) } ?: "—")
            append(" | próximo Moodle=").append(moodle?.let { fmt.format(Date(it)) } ?: "—")
            append(" | freqMoodle=").append(SyncPrefs.moodleFrequencyHours).append("h")
            append(" | sóIlimitadas=").append(SyncPrefs.syncOnlyOnUnmeteredNetworks)
            append(" | responsável=").append(AppMode.isResponsavelFinanceiro)
        }
    }
}