package com.marinov.openfei.login

import android.content.Context
import android.util.Log
import com.marinov.openfei.core.CoreResult
import com.marinov.openfei.core.OpenFeiCore
import com.marinov.openfei.util.WebViewHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

sealed class SessionResult {
    data object Ok : SessionResult()
    data object NoCredentials : SessionResult()
    data class NetworkError(val message: String) : SessionResult()
    data class AuthError(val message: String) : SessionResult()
}

object RustSession {

    private const val TAG = "RustSession"
    private const val FEI_RENEW_INTERVAL_MS = 10L * 60 * 1000L

    private lateinit var appContext: Context

    @Volatile
    private var lastFeiLoginTime = 0L

    /**
     * ★ Evita que o interceptador do OpenFeiCore entre em recursão:
     * o próprio login não pode disparar renovação de sessão.
     */
    @Volatile
    var isRenewing: Boolean = false
        private set

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun invalidate() {
        lastFeiLoginTime = 0L
        Log.d(TAG, "Cache de sessão FEI invalidado")
    }

    fun markLoggedIn() {
        lastFeiLoginTime = System.currentTimeMillis()
        if (::appContext.isInitialized) AuthState.recordSuccess(appContext)
    }

    /** Zera o cache sem tocar no contador de falhas (usado no retry). */
    fun invalidateCacheOnly() {
        lastFeiLoginTime = 0L
    }

    private fun isFresh(): Boolean =
        lastFeiLoginTime > 0 &&
                System.currentTimeMillis() - lastFeiLoginTime < FEI_RENEW_INTERVAL_MS

    suspend fun ensureFeiSession(): SessionResult = withContext(Dispatchers.IO) {
        if (isFresh()) return@withContext SessionResult.Ok

        SessionLock.mutex.withLock {
            if (isFresh()) return@withLock SessionResult.Ok

            val user = CredentialsStore.user(appContext)
            val pass = CredentialsStore.pass(appContext)
            if (user.isEmpty() || pass.isEmpty()) {
                return@withLock SessionResult.NoCredentials
            }

            isRenewing = true
            try {
                when (val result = OpenFeiCore.login(user, pass)) {
                    is CoreResult.Success -> {
                        val data = result.data
                        when {
                            data.success -> {
                                markLoggedIn()
                                withContext(Dispatchers.Main) {
                                    WebViewHelper.ensureWebView(appContext)
                                }
                                LoginLogic.injectCookies(data.cookies)
                                Log.d(TAG, "Sessão FEI renovada via Rust")
                                SessionResult.Ok
                            }

                            data.isNetworkError ->
                                SessionResult.NetworkError(data.errorMessage)

                            else -> {
                                // ★ PROBLEMA 3: conta a falha, mas NÃO desloga aqui.
                                AuthState.recordAuthFailure(appContext)
                                SessionResult.AuthError(data.errorMessage)
                            }
                        }
                    }

                    is CoreResult.Error ->
                        SessionResult.NetworkError(result.message)
                }
            } finally {
                isRenewing = false
            }
        }
    }
}