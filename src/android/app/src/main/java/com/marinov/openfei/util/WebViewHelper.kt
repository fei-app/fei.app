package com.marinov.openfei.util

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebView

/**
 * Garante que um WebView exista em memória para que o CookieManager
 * funcione corretamente, inclusive quando o app está em segundo plano.
 * ★ BUG 62: agora limita o número de tentativas de recuperação para não
 * entrar em loop infinito se o provider de WebView estiver desabilitado.
 */
@SuppressLint("StaticFieldLeak")
object WebViewHelper {

    private const val TAG = "WebViewHelper"
    private const val MAX_RECOVER_ATTEMPTS = 3

    @SuppressLint("StaticFieldLeak")
    @Volatile
    private var webView: WebView? = null
    @Volatile
    private var recoverAttempts: Int = 0

    private val mainHandler = Handler(Looper.getMainLooper())

    fun ensureWebView(context: Context) {
        if (webView != null) return
        val appContext = context.applicationContext
        if (Looper.myLooper() == Looper.getMainLooper()) {
            createWebView(appContext)
        } else {
            mainHandler.post { createWebView(appContext) }
        }
    }

    private fun createWebView(context: Context) {
        try {
            if (webView != null) return
            val wv = WebView(context)
            wv.settings.javaScriptEnabled = false
            wv.settings.domStorageEnabled = true
            CookieManager.getInstance().setAcceptCookie(true)
            webView = wv
            recoverAttempts = 0
            Log.d(TAG, "WebView criado com sucesso")
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao criar WebView, tentando recuperação", e)
            tryRecoverWebView(context)
        } catch (e: Throwable) {
            Log.e(TAG, "Erro fatal ao criar WebView", e)
        }
    }

    private fun tryRecoverWebView(context: Context) {
        if (recoverAttempts >= MAX_RECOVER_ATTEMPTS) {
            Log.e(TAG, "Limite de recuperação do WebView atingido ($MAX_RECOVER_ATTEMPTS). Abortando.")
            return
        }
        recoverAttempts++
        try {
            webView?.destroy()
            webView = null
            val delay = (1000L * recoverAttempts).coerceAtMost(5000L)
            mainHandler.postDelayed({
                try {
                    createWebView(context)
                } catch (e: Exception) {
                    Log.e(TAG, "Falha na recuperação #$recoverAttempts", e)
                }
            }, delay)
        } catch (e: Exception) {
            Log.e(TAG, "Falha total na recuperação do WebView", e)
        }
    }

    fun getCookiesSafely(url: String): String {
        return try {
            CookieManager.getInstance().getCookie(url) ?: ""
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao obter cookies", e)
            ""
        }
    }
}