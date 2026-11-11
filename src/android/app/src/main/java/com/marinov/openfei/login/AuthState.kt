package com.marinov.openfei.login

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

/**
 * ★ PROBLEMA 3: contador de falhas de autenticação CONFIRMADAS.
 *
 * Antes, uma única resposta ambígua do portal (queda de sinal, 502, portal
 * cativo) era interpretada como sessão expirada e o app deslogava o usuário.
 * Agora só consideramos as credenciais inválidas após N falhas consecutivas
 * de autenticação real. Erro de rede nunca incrementa este contador.
 */
object AuthState {

    private const val TAG = "AuthState"
    private const val PREFS = "auth_state_prefs"
    private const val KEY_AUTH_FAILS = "consecutive_auth_failures"
    private const val KEY_LAST_OK = "last_auth_success_at"

    /** Falhas consecutivas toleradas antes de exigir login manual. */
    const val MAX_AUTH_FAILURES = 3

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun authFailures(context: Context): Int =
        prefs(context).getInt(KEY_AUTH_FAILS, 0)

    fun recordAuthFailure(context: Context): Int {
        val next = authFailures(context) + 1
        prefs(context).edit().putInt(KEY_AUTH_FAILS, next).apply()
        Log.w(TAG, "Falha de autenticação confirmada ($next/$MAX_AUTH_FAILURES)")
        return next
    }

    fun recordSuccess(context: Context) {
        prefs(context).edit()
            .putInt(KEY_AUTH_FAILS, 0)
            .putLong(KEY_LAST_OK, System.currentTimeMillis())
            .apply()
    }

    fun reset(context: Context) {
        prefs(context).edit().putInt(KEY_AUTH_FAILS, 0).apply()
    }

    /** Só é verdade quando o servidor recusou as credenciais N vezes seguidas. */
    fun isAuthBlocked(context: Context): Boolean =
        authFailures(context) >= MAX_AUTH_FAILURES
}