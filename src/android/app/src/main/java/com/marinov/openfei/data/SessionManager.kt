package com.marinov.openfei.data

import android.content.Context
import android.util.Log
import com.marinov.openfei.util.WebViewHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

object SessionManager {
    private const val TAG = "SessionManager"
    private lateinit var appContext: Context

    // Mutexes separados para FEI e Moodle
    private val sessionMutex = Mutex()
    private val moodleSessionMutex = Mutex()

    // Timestamps de última renovação
    private var lastRenewalTime = 0L
    private var lastMoodleRenewalTime = 0L

    // Intervalos de renovação
    private const val RENEWAL_INTERVAL_MS = 600_000        // 10 minutos para FEI

    // Constantes de status para compatibilidade
    const val STATUS_OFFLINE = "0"
    const val STATUS_ONLINE_OK = "1"
    const val STATUS_LOGIN_NEEDED = "A"

    // Proteção de cookies do Moodle durante uso ativo do WebView
    @Volatile
    private var moodleCookiesProtected = false

    fun init(context: Context) {
        appContext = context.applicationContext
        WebViewHelper.ensureWebView(appContext)
    }

    fun protegerCookiesMoodle() {
        moodleCookiesProtected = true
        Log.d(TAG, "Cookies do Moodle protegidos (WebView em uso)")
    }

    fun desprotegerCookiesMoodle() {
        moodleCookiesProtected = false
        Log.d(TAG, "Cookies do Moodle desprotegidos")
    }

    suspend fun checkConnectionAndSession(): String = withContext(Dispatchers.IO) {
        val isOnline = NetworkChecker.isOnline()
        if (!isOnline) {
            Log.d(TAG, "checkConnectionAndSession → NCSI diz que está offline")
            return@withContext STATUS_OFFLINE
        }

        try {
            renewSession()
            Log.d(TAG, "checkConnectionAndSession → sessão FEI renovada com sucesso")
            STATUS_ONLINE_OK
        } catch (_: SessionExpiredException) {
            Log.w(TAG, "checkConnectionAndSession → sessão FEI expirada mas está online → precisa login")
            STATUS_LOGIN_NEEDED
        } catch (e: Exception) {
            Log.e(TAG, "checkConnectionAndSession → erro inesperado", e)
            STATUS_LOGIN_NEEDED
        }
    }

    suspend fun renewSession() {
        val now = System.currentTimeMillis()
        if (now - lastRenewalTime < RENEWAL_INTERVAL_MS) {
            Log.d(TAG, "Sessão FEI já renovada recentemente, pulando login completo.")
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
                Log.e(TAG, "Erro no login silencioso FEI", e)
                LoginResult(false, e.message ?: "", isNetworkError = true)
            }

            if (!loginResult.success) {
                throw SessionExpiredException("Não foi possível renovar a sessão FEI — login silencioso falhou")
            }

            lastRenewalTime = System.currentTimeMillis()
        }
    }

    // ★ REMOVIDO: fetchPage() que usava Jsoup
    // Os repositórios agora usam diretamente o OpenFeiCore (Rust)

    suspend fun forcarRenovacaoCookiesMoodle(): Boolean = withContext(Dispatchers.IO) {
        moodleSessionMutex.withLock {
            Log.d(TAG, "Forçando renovação REAL dos cookies do Moodle (login completo via formulário)")
            val result = LoginLogic.forcarLoginCookiesMoodle(appContext)
            if (result.success) {
                lastMoodleRenewalTime = System.currentTimeMillis()
                Log.d(TAG, "Cookies do Moodle renovados com sucesso via login completo | token=${result.token != null}")

                // ★ Log de verificação: mostra os cookies que o CookieManager tem para o Moodle
                val cookieStr = android.webkit.CookieManager.getInstance().getCookie("https://moodle.fei.edu.br")
                Log.d(TAG, "Cookies atuais no CookieManager para moodle.fei.edu.br: ${cookieStr?.take(200) ?: "NENHUM"}")
            } else {
                Log.w(TAG, "Falha ao renovar cookies do Moodle: ${result.errorMessage}")
            }
            result.success
        }
    }

}