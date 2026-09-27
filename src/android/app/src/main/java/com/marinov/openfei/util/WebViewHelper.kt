package com.marinov.openfei.util

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebView

/**
 * Garante que um WebView exista em memória para que o CookieManager
 * funcione corretamente, inclusive quando o app está em segundo plano.
 * Todas as operações são protegidas contra crash do WebView.
 */
object WebViewHelper {
    private const val TAG = "WebViewHelper"

    @Volatile
    private var webView: WebView? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Garante que o WebView exista. Thread-safe: se chamado fora da
     * thread principal, faz post para ela.
     */
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
            // Garante que o CookieManager esteja funcional
            CookieManager.getInstance().setAcceptCookie(true)
            webView = wv
            Log.d(TAG, "WebView criado com sucesso")
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao criar WebView, tentando recuperação", e)
            tryRecoverWebView(context)
        } catch (e: Throwable) {
            Log.e(TAG, "Erro fatal ao criar WebView", e)
        }
    }

    private fun tryRecoverWebView(context: Context) {
        try {
            webView?.destroy()
            webView = null
            mainHandler.postDelayed({
                try { createWebView(context) }
                catch (e: Exception) { Log.e(TAG, "Falha na recuperação do WebView", e) }
            }, 1000)
        } catch (e: Exception) {
            Log.e(TAG, "Falha total na recuperação do WebView", e)
        }
    }

    /** Obtém cookies de forma segura. Retorna "" em caso de erro. */
    fun getCookiesSafely(url: String): String {
        return try {
            CookieManager.getInstance().getCookie(url) ?: ""
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao obter cookies", e)
            ""
        }
    }

}