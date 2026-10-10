package com.marinov.openfei.app

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * Estado global do "Modo responsável financeiro".
 * Quando ativo, o OpenFEI se comporta como o BoletosFEI:
 * some a navegação inferior/lateral, o BoletosFragment vira a tela
 * principal, e notificações de notas/horário são suprimidas.
 */
object AppMode {
    private const val PREFS_NAME = "app_mode_prefs"
    private const val KEY_MODO_RESPONSAVEL = "modo_responsavel_financeiro"

    private lateinit var appContext: Context

    @Volatile
    private var cachedValue: Boolean? = null

    fun init(context: Context) {
        if (!::appContext.isInitialized) {
            appContext = context.applicationContext
        }
    }

    var isResponsavelFinanceiro: Boolean
        get() {
            cachedValue?.let { return it }
            val value = prefs().getBoolean(KEY_MODO_RESPONSAVEL, false)
            cachedValue = value
            return value
        }
        set(value) {
            cachedValue = value
            prefs().edit { putBoolean(KEY_MODO_RESPONSAVEL, value) }
        }

    private fun prefs(): SharedPreferences =
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}