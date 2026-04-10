package com.marinov.openfei

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class LoginWorker(appContext: Context, workerParams: WorkerParameters) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val prefs = applicationContext.getSharedPreferences(LoginActivity.PREFS_LOGIN, Context.MODE_PRIVATE)
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
                // Se falhou, já foi setado false dentro de performLogin
                Result.retry()
            }
        } catch (e: Exception) {
            Log.e("LoginWorker", "Exceção inesperada no LoginWorker", e)
            prefs.edit { putBoolean(LoginActivity.KEY_IS_LOGGED_IN, false) }
            Result.retry()
        }
    }
}