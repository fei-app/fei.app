package com.marinov.openfei.login

import android.content.Context
import android.util.Log
import android.webkit.CookieManager
import com.marinov.openfei.cache.CacheHelper
import com.marinov.openfei.core.OpenFeiCore
import com.marinov.openfei.network.NetworkChecker
import com.marinov.openfei.util.WebViewHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

object SessionManager {

    private const val TAG = "SessionManager"

    const val STATUS_OFFLINE = "0"
    const val STATUS_ONLINE_OK = "1"
    const val STATUS_LOGIN_NEEDED = "A"

    private lateinit var appContext: Context

    @Volatile
    private var moodleCookiesProtected = false

    fun init(context: Context) {
        appContext = context.applicationContext
        WebViewHelper.ensureWebView(appContext)
    }

    fun protegerCookiesMoodle() { moodleCookiesProtected = true }

    fun desprotegerCookiesMoodle() { moodleCookiesProtected = false }

    /**
     * ★ PROBLEMA 3: só devolve LOGIN_NEEDED quando
     *  (a) não há credenciais salvas, ou
     *  (b) o servidor recusou as credenciais MAX_AUTH_FAILURES vezes seguidas.
     *
     * Falha de rede, HTML inesperado, 502, portal cativo → OFFLINE (nunca logout).
     * Assim o app continua tentando renovar em segundo plano a cada request.
     */
    suspend fun checkConnectionAndSession(): String = withContext(Dispatchers.IO) {
        if (!CredentialsStore.hasCredentials(appContext)) {
            return@withContext STATUS_LOGIN_NEEDED
        }

        if (!NetworkChecker.isOnline()) {
            return@withContext STATUS_OFFLINE
        }

        when (val result = RustSession.ensureFeiSession()) {
            is SessionResult.Ok -> STATUS_ONLINE_OK

            is SessionResult.NetworkError -> {
                Log.w(TAG, "Rede instável ao validar sessão: ${result.message}")
                STATUS_OFFLINE
            }

            is SessionResult.AuthError -> {
                if (AuthState.isAuthBlocked(appContext)) {
                    Log.w(TAG, "Credenciais recusadas repetidamente → login manual")
                    STATUS_LOGIN_NEEDED
                } else {
                    // Ainda dentro da tolerância: deixa os fetches tentarem
                    // de novo (o interceptador do OpenFeiCore cuida disso).
                    STATUS_ONLINE_OK
                }
            }

            SessionResult.NoCredentials -> STATUS_LOGIN_NEEDED
        }
    }

    suspend fun forcarRenovacaoCookiesMoodle(): Boolean = withContext(Dispatchers.IO) {
        SessionLock.mutex.withLock {
            OpenFeiCore.clearMoodleSession()
            val result = LoginLogic.forcarLoginCookiesMoodle(appContext)
            if (!result.success) {
                Log.w(TAG, "Falha ao renovar cookies do Moodle: ${result.errorMessage}")
            }
            result.success
        }
    }

    /**
     * ★ PROBLEMA 3: `markLoggedOut` agora é explícito.
     * Limpar cache/cookies NÃO implica mais perder o estado de login.
     */
    suspend fun invalidateLocalSession(
        clearData: Boolean = true,
        markLoggedOut: Boolean = false
    ) = withContext(Dispatchers.IO) {
        SessionLock.mutex.withLock {
            OpenFeiCore.clearSession()
            RustSession.invalidate()

            if (markLoggedOut) {
                CredentialsStore.setLoggedIn(appContext, false)
            }

            if (clearData) {
                runCatching { CacheHelper.clearAllCacheFiles() }
            }

            if (!moodleCookiesProtected) {
                runCatching {
                    val cm = CookieManager.getInstance()
                    cm.removeAllCookies(null)
                    cm.flush()
                }
            }
            Log.d(TAG, "Sessão local invalidada (clearData=$clearData, markLoggedOut=$markLoggedOut)")
        }
    }
}