package com.marinov.openfei.data

import android.content.Context
import android.util.Log
import com.marinov.openfei.util.WebViewHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.io.IOException

object SessionManager {
    private const val TAG = "SessionManager"
    private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 16; sdk_gphone64_x86_64 Build/BE2A.250530.026.D1; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/133.0.6943.137 Mobile Safari/537.36"
    private lateinit var appContext: Context

    private val sessionMutex = Mutex()
    private var lastRenewalTime = 0L
    private const val RENEWAL_INTERVAL_MS = 600_000

    // Constantes de status para compatibilidade
    const val STATUS_OFFLINE = "0"
    const val STATUS_ONLINE_OK = "1"
    const val STATUS_LOGIN_NEEDED = "A"

    fun init(context: Context) {
        appContext = context.applicationContext
        WebViewHelper.ensureWebView(appContext)
    }

    /**
     * Verifica conexão e sessão de forma centralizada.
     * Fluxo:
     * 1. Verifica NCSI para saber se há internet real
     * 2. Se offline → retorna STATUS_OFFLINE
     * 3. Se online → tenta renovar sessão
     * 4. Se sessão falhar e está online → STATUS_LOGIN_NEEDED
     * 5. Se sessão OK → STATUS_ONLINE_OK
     */
    suspend fun checkConnectionAndSession(): String = withContext(Dispatchers.IO) {
        val isOnline = NetworkChecker.isOnline()

        if (!isOnline) {
            Log.d(TAG, "checkConnectionAndSession → NCSI diz que está offline")
            return@withContext STATUS_OFFLINE
        }

        // Está online, tenta renovar sessão
        try {
            renewSession()
            Log.d(TAG, "checkConnectionAndSession → sessão renovada com sucesso")
            STATUS_ONLINE_OK
        } catch (e: SessionExpiredException) {
            // Sessão falhou, mas está online → precisa de login
            Log.w(TAG, "checkConnectionAndSession → sessão expirada mas está online → precisa login")
            STATUS_LOGIN_NEEDED
        } catch (e: Exception) {
            Log.e(TAG, "checkConnectionAndSession → erro inesperado", e)
            STATUS_LOGIN_NEEDED
        }
    }

    suspend fun renewSession() {
        val now = System.currentTimeMillis()
        if (now - lastRenewalTime < RENEWAL_INTERVAL_MS) {
            Log.d(TAG, "Sessão já renovada recentemente, pulando login completo.")
            return
        }
        sessionMutex.withLock {
            val nowInside = System.currentTimeMillis()
            if (nowInside - lastRenewalTime < RENEWAL_INTERVAL_MS) {
                return@withLock
            }
            val loginResult = try {
                LoginLogic.performLoginSilent(appContext)
            } catch (e: Exception) {
                Log.e(TAG, "Erro no login silencioso", e)
                LoginResult(false, e.message ?: "", isNetworkError = true)
            }
            if (!loginResult.success) {
                throw SessionExpiredException("Não foi possível renovar a sessão — login silencioso falhou")
            }
            lastRenewalTime = System.currentTimeMillis()
        }
    }

    suspend fun garantirSessaoValida() = withContext(Dispatchers.IO) {
        renewSession()
    }

    suspend fun <T> withSecureSession(block: suspend () -> T): T {
        return sessionMutex.withLock {
            val now = System.currentTimeMillis()
            if (now - lastRenewalTime >= RENEWAL_INTERVAL_MS) {
                val loginResult = try {
                    LoginLogic.performLoginSilent(appContext)
                } catch (e: Exception) {
                    Log.e(TAG, "Erro no login silencioso dentro de withSecureSession", e)
                    LoginResult(false, e.message ?: "", isNetworkError = true)
                }
                if (!loginResult.success) {
                    throw SessionExpiredException("Não foi possível renovar a sessão")
                }
                lastRenewalTime = System.currentTimeMillis()
            }
            block()
        }
    }

    @Throws(IOException::class)
    suspend fun fetchPage(url: String): org.jsoup.nodes.Document = withContext(Dispatchers.IO) {
        withSecureSession {
            val cookies = WebViewHelper.getCookiesSafely(url)
            try {
                val conn = Jsoup.connect(url)
                    .userAgent(USER_AGENT)
                    .timeout(20000)
                if (cookies.isNotBlank()) {
                    conn.header("Cookie", cookies)
                }
                conn.get()
            } catch (e: IOException) {
                Log.e(TAG, "Erro de rede ao buscar $url", e)
                throw e
            }
        }
    }
}