package com.marinov.openfei.data

import android.content.Context
import android.util.Log
import com.marinov.openfei.util.WebViewHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.io.IOException

object SessionManager {
    private const val TAG = "SessionManager"
    private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 16; sdk_gphone64_x86_64 Build/BE2A.250530.026.D1; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/133.0.6943.137 Mobile Safari/537.36"

    private lateinit var appContext: Context

    fun init(context: Context) {
        appContext = context.applicationContext
        WebViewHelper.ensureWebView(appContext)
    }

    suspend fun renewSession() {
        val loginOk = try {
            LoginLogic.performLoginSilent(appContext)
        } catch (e: Exception) {
            Log.e(TAG, "Erro no login silencioso", e)
            false
        }
        if (!loginOk) {
            throw SessionExpiredException("Não foi possível renovar a sessão — login silencioso falhou")
        }
    }

    suspend fun garantirSessaoValida() = withContext(Dispatchers.IO) {
        renewSession()
    }

    @Throws(IOException::class)
    suspend fun fetchPage(url: String): org.jsoup.nodes.Document = withContext(Dispatchers.IO) {
        renewSession()
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