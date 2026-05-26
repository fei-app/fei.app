package com.marinov.openfei

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class LoginWorker(appContext: Context, workerParams: WorkerParameters) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        // Correção: Agora lê os dados do EncryptedSharedPreferences
        val prefs = LoginActivity.getEncryptedPrefs(applicationContext)
        val user = prefs.getString(LoginActivity.KEY_USER, "")
        val pass = prefs.getString(LoginActivity.KEY_PASS, "")

        if (user.isNullOrEmpty() || pass.isNullOrEmpty()) {
            Log.w("LoginWorker", "Credenciais não encontradas. Marcando como deslogado.")
            prefs.edit { putBoolean(LoginActivity.KEY_IS_LOGGED_IN, false) }
            return Result.failure()
        }

        return try {
            val result = LoginLogic.performLogin(user, pass, applicationContext)
            if (result.success) {
                Log.d("LoginWorker", "Login renovado com sucesso em background.")
                Result.success()
            } else {
                Log.e("LoginWorker", "Falha ao renovar login em background: ${result.errorMessage}")
                // Correção: Diferenciar falha de credenciais (falha definitiva) de erro de rede (retry)
                if (result.errorMessage.contains("incorretos", ignoreCase = true) ||
                    result.errorMessage.contains("senha", ignoreCase = true)
                ) {
                    Result.failure()
                } else {
                    Result.retry()
                }
            }
        } catch (e: Exception) {
            Log.e("LoginWorker", "Exceção inesperada no LoginWorker", e)
            prefs.edit { putBoolean(LoginActivity.KEY_IS_LOGGED_IN, false) }
            Result.retry()
        }
    }
}