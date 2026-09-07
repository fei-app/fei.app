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

    // Mutexes separados para FEI e Moodle
    private val sessionMutex = Mutex()
    private val moodleSessionMutex = Mutex()

    // Timestamps de última renovação
    private var lastRenewalTime = 0L
    private var lastMoodleRenewalTime = 0L

    // Intervalos de renovação
    private const val RENEWAL_INTERVAL_MS = 600_000        // 10 minutos para FEI
    private const val RENEWAL_INTERVAL_MS_MOODLE = 10_200_000  // 2h50min para Moodle

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

    fun isMoodleCookiesProtected(): Boolean = moodleCookiesProtected

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
        } catch (e: SessionExpiredException) {
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

    /**
     * Renova a sessão Moodle (checagem leve — reaproveita token existente
     * se ainda for válido). Respeita a proteção de cookies.
     */
    suspend fun renewMoodleSession() {
        if (moodleCookiesProtected) {
            Log.d(TAG, "Renovação da sessão Moodle pulada — cookies protegidos (WebView em uso)")
            return
        }

        val now = System.currentTimeMillis()
        if (now - lastMoodleRenewalTime < RENEWAL_INTERVAL_MS_MOODLE) {
            Log.d(TAG, "Sessão Moodle já renovada recentemente, pulando login completo.")
            return
        }

        moodleSessionMutex.withLock {
            if (moodleCookiesProtected) {
                Log.d(TAG, "Renovação da sessão Moodle pulada dentro do lock — cookies protegidos")
                return@withLock
            }

            val nowInside = System.currentTimeMillis()
            if (nowInside - lastMoodleRenewalTime < RENEWAL_INTERVAL_MS_MOODLE) {
                return@withLock
            }

            val token = LoginLogic.garantirMoodleToken(appContext)

            if (token == null) {
                throw SessionExpiredException("Não foi possível renovar a sessão Moodle — login falhou")
            }

            lastMoodleRenewalTime = System.currentTimeMillis()
            Log.d(TAG, "Sessão Moodle renovada com sucesso")
        }
    }

    suspend fun garantirSessaoValida() = withContext(Dispatchers.IO) {
        renewSession()
    }

    suspend fun garantirSessaoMoodleValida() = withContext(Dispatchers.IO) {
        renewMoodleSession()
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
                    throw SessionExpiredException("Não foi possível renovar a sessão FEI")
                }
                lastRenewalTime = System.currentTimeMillis()
            }
            block()
        }
    }

    suspend fun <T> withSecureMoodleSession(block: suspend () -> T): T {
        return moodleSessionMutex.withLock {
            val now = System.currentTimeMillis()
            if (!moodleCookiesProtected && now - lastMoodleRenewalTime >= RENEWAL_INTERVAL_MS_MOODLE) {
                val token = LoginLogic.garantirMoodleToken(appContext)

                if (token == null) {
                    throw SessionExpiredException("Não foi possível renovar a sessão Moodle")
                }
                lastMoodleRenewalTime = System.currentTimeMillis()
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

    /**
     * Força renovação "leve" da sessão Moodle (ignora intervalo, mas ainda
     * reaproveita o token da API se este continuar válido). Mantido para
     * compatibilidade com outros pontos do app.
     */
    suspend fun forcarRenovacaoMoodle() = withContext(Dispatchers.IO) {
        Log.d(TAG, "Forçando renovação (leve) da sessão Moodle")
        lastMoodleRenewalTime = 0L
        renewMoodleSession()
    }

    /**
     * ★ NOVO: força a renovação REAL dos cookies do Moodle, refazendo o
     * login por formulário (username/senha) independentemente do token da
     * API já ser válido ou não. Necessário porque o token da API e o
     * cookie de sessão do navegador (usado pelo WebView) são coisas
     * independentes — o token pode continuar válido mesmo com o cookie
     * de sessão do navegador expirado. Ignora a flag de proteção
     * propositalmente (o chamador deve desproteger antes de chamar isto).
     */
    suspend fun forcarRenovacaoCookiesMoodle(): Boolean = withContext(Dispatchers.IO) {
        moodleSessionMutex.withLock {
            Log.d(TAG, "Forçando renovação REAL dos cookies do Moodle (login completo via formulário)")
            val result = LoginLogic.forcarLoginCookiesMoodle(appContext)
            if (result.success) {
                lastMoodleRenewalTime = System.currentTimeMillis()
                Log.d(TAG, "Cookies do Moodle renovados com sucesso via login completo")
            } else {
                Log.w(TAG, "Falha ao renovar cookies do Moodle via login completo: ${result.errorMessage}")
            }
            result.success
        }
    }

    fun isMoodleSessionValid(): Boolean {
        val now = System.currentTimeMillis()
        return (now - lastMoodleRenewalTime) < RENEWAL_INTERVAL_MS_MOODLE
    }

    fun atualizarTimestampMoodle() {
        lastMoodleRenewalTime = System.currentTimeMillis()
        Log.d(TAG, "Timestamp Moodle atualizado manualmente")
    }
}