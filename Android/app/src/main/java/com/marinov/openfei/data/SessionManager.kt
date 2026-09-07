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

    fun init(context: Context) {
        appContext = context.applicationContext
        WebViewHelper.ensureWebView(appContext)
    }

    /**
     * Verifica conexão e sessão FEI de forma centralizada.
     * NÃO verifica Moodle - isso é feito separadamente.
     * Fluxo:
     * 1. Verifica NCSI para saber se há internet real
     * 2. Se offline → retorna STATUS_OFFLINE
     * 3. Se online → tenta renovar sessão FEI
     * 4. Se sessão falhar e está online → STATUS_LOGIN_NEEDED
     * 5. Se sessão OK → STATUS_ONLINE_OK
     */
    suspend fun checkConnectionAndSession(): String = withContext(Dispatchers.IO) {
        val isOnline = NetworkChecker.isOnline()
        if (!isOnline) {
            Log.d(TAG, "checkConnectionAndSession → NCSI diz que está offline")
            return@withContext STATUS_OFFLINE
        }

        // Está online, tenta renovar sessão FEI
        try {
            renewSession()
            Log.d(TAG, "checkConnectionAndSession → sessão FEI renovada com sucesso")
            STATUS_ONLINE_OK
        } catch (e: SessionExpiredException) {
            // Sessão falhou, mas está online → precisa de login
            Log.w(TAG, "checkConnectionAndSession → sessão FEI expirada mas está online → precisa login")
            STATUS_LOGIN_NEEDED
        } catch (e: Exception) {
            Log.e(TAG, "checkConnectionAndSession → erro inesperado", e)
            STATUS_LOGIN_NEEDED
        }
    }

    /**
     * Renova a sessão FEI (interage.fei.edu.br)
     */
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
     * Renova a sessão Moodle (moodle.fei.edu.br)
     */
    suspend fun renewMoodleSession() {
        val now = System.currentTimeMillis()
        if (now - lastMoodleRenewalTime < RENEWAL_INTERVAL_MS_MOODLE) {
            Log.d(TAG, "Sessão Moodle já renovada recentemente, pulando login completo.")
            return
        }

        moodleSessionMutex.withLock {
            val nowInside = System.currentTimeMillis()
            if (nowInside - lastMoodleRenewalTime < RENEWAL_INTERVAL_MS_MOODLE) {
                return@withLock
            }

            // Tenta garantir o token do Moodle (isso faz login se necessário)
            val token = LoginLogic.garantirMoodleToken(appContext)

            if (token == null) {
                throw SessionExpiredException("Não foi possível renovar a sessão Moodle — login falhou")
            }

            lastMoodleRenewalTime = System.currentTimeMillis()
            Log.d(TAG, "Sessão Moodle renovada com sucesso")
        }
    }

    /**
     * Garante que a sessão FEI está válida
     */
    suspend fun garantirSessaoValida() = withContext(Dispatchers.IO) {
        renewSession()
    }

    /**
     * Garante que a sessão Moodle está válida
     */
    suspend fun garantirSessaoMoodleValida() = withContext(Dispatchers.IO) {
        renewMoodleSession()
    }

    /**
     * Executa um bloco com sessão FEI garantida
     */
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

    /**
     * Executa um bloco com sessão Moodle garantida
     */
    suspend fun <T> withSecureMoodleSession(block: suspend () -> T): T {
        return moodleSessionMutex.withLock {
            val now = System.currentTimeMillis()
            if (now - lastMoodleRenewalTime >= RENEWAL_INTERVAL_MS_MOODLE) {
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
     * Força renovação da sessão Moodle (ignora intervalo)
     * Usado quando o WebView detecta acesso a /login/
     */
    suspend fun forcarRenovacaoMoodle() = withContext(Dispatchers.IO) {
        Log.d(TAG, "Forçando renovação da sessão Moodle")
        lastMoodleRenewalTime = 0L
        renewMoodleSession()
    }

    /**
     * Verifica se a sessão Moodle está dentro do intervalo válido
     */
    fun isMoodleSessionValid(): Boolean {
        val now = System.currentTimeMillis()
        return (now - lastMoodleRenewalTime) < RENEWAL_INTERVAL_MS_MOODLE
    }

    /**
     * Atualiza o timestamp de renovação do Moodle (usado após login manual no WebView)
     */
    fun atualizarTimestampMoodle() {
        lastMoodleRenewalTime = System.currentTimeMillis()
        Log.d(TAG, "Timestamp Moodle atualizado manualmente")
    }
}