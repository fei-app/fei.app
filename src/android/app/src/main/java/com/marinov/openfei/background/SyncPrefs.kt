package com.marinov.openfei.background

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * Preferências de sincronização em segundo plano.
 */
object SyncPrefs {

    private const val PREFS_NAME = "sync_prefs"
    private const val KEY_MOODLE_FREQ_HOURS = "moodle_frequency_hours"
    private const val KEY_ONLY_UNMETERED = "sync_only_unmetered_networks"

    const val DEFAULT_MOODLE_FREQ_HOURS = 1
    const val DEFAULT_ONLY_UNMETERED = true

    val ALLOWED_MOODLE_FREQUENCIES = listOf(1, 2, 3, 6)

    private lateinit var appContext: Context

    fun init(context: Context) {
        if (!::appContext.isInitialized) {
            appContext = context.applicationContext
        }
    }

    private fun prefs(): SharedPreferences =
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Frequência de atualização dos eventos do Moodle (em horas).
     * Valores válidos: 1, 2, 3, 6. Padrão: 1.
     */
    var moodleFrequencyHours: Int
        get() {
            val value = prefs().getInt(KEY_MOODLE_FREQ_HOURS, DEFAULT_MOODLE_FREQ_HOURS)
            return if (value in ALLOWED_MOODLE_FREQUENCIES) value else DEFAULT_MOODLE_FREQ_HOURS
        }
        set(value) {
            val safe = if (value in ALLOWED_MOODLE_FREQUENCIES) value else DEFAULT_MOODLE_FREQ_HOURS
            prefs().edit { putInt(KEY_MOODLE_FREQ_HOURS, safe) }
        }

    /**
     * Se true, o app só sincroniza em segundo plano quando estiver em redes
     * consideradas ilimitadas (Wi-Fi etc.), nunca em redes limitadas (dados móveis).
     * Padrão: true.
     */
    var syncOnlyOnUnmeteredNetworks: Boolean
        get() = prefs().getBoolean(KEY_ONLY_UNMETERED, DEFAULT_ONLY_UNMETERED)
        set(value) {
            prefs().edit { putBoolean(KEY_ONLY_UNMETERED, value) }
        }
}