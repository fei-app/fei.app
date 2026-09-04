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

    // Mutex para serializar o acesso à sessão e aos cookies
    private val sessionMutex = Mutex()

    // Cache para evitar renovações repetidas e desnecessárias que limpam os cookies
    private var lastRenewalTime = 0L
    private const val RENEWAL_INTERVAL_MS = 60_000 // 1 minuto

    fun init(context: Context) {
        appContext = context.applicationContext
        WebViewHelper.ensureWebView(appContext)
    }

    suspend fun renewSession() {
        val now = System.currentTimeMillis()
        // Se a sessão já foi renovada recentemente, não faz o login completo (evita limpar os cookies)
        if (now - lastRenewalTime < RENEWAL_INTERVAL_MS) {
            Log.d(TAG, "Sessão já renovada recentemente, pulando login completo.")
            return
        }

        sessionMutex.withLock {
            // Double-checked locking para garantir thread-safety
            val nowInside = System.currentTimeMillis()
            if (nowInside - lastRenewalTime < RENEWAL_INTERVAL_MS) {
                return@withLock
            }

            val loginOk = try {
                LoginLogic.performLoginSilent(appContext)
            } catch (e: Exception) {
                Log.e(TAG, "Erro no login silencioso", e)
                false
            }
            if (!loginOk) {
                throw SessionExpiredException("Não foi possível renovar a sessão — login silencioso falhou")
            }
            lastRenewalTime = System.currentTimeMillis()
        }
    }

    suspend fun garantirSessaoValida() = withContext(Dispatchers.IO) {
        renewSession()
    }

    /**
     * Executa um bloco de código garantindo que a sessão está válida e protegendo
     * contra renovações concorrentes que possam limpar os cookies.
     * Ideal para operações que fazem múltiplas requisições ou leem cookies diretamente.
     */
    suspend fun <T> withSecureSession(block: suspend () -> T): T {
        return sessionMutex.withLock {
            val now = System.currentTimeMillis()
            if (now - lastRenewalTime >= RENEWAL_INTERVAL_MS) {
                val loginOk = try {
                    LoginLogic.performLoginSilent(appContext)
                } catch (e: Exception) {
                    Log.e(TAG, "Erro no login silencioso dentro de withSecureSession", e)
                    false
                }
                if (!loginOk) {
                    throw SessionExpiredException("Não foi possível renovar a sessão")
                }
                lastRenewalTime = System.currentTimeMillis()
            }
            block()
        }
    }

    @Throws(IOException::class)
    suspend fun fetchPage(url: String): org.jsoup.nodes.Document = withContext(Dispatchers.IO) {
        // O fetchPage agora usa o withSecureSession, protegendo a leitura dos cookies
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