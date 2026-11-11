package com.marinov.openfei.ui.webview

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.os.Build
import android.os.Environment
import android.view.View
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.Toast
import androidx.core.net.toUri
import com.marinov.openfei.R

object WebViewConfig {

    const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 16; sdk_gphone64_x86_64 Build/BE2A.250530.026.D1; wv) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/133.0.6943.137 Mobile Safari/537.36"

    @SuppressLint("SetJavaScriptEnabled")
    fun configure(webView: WebView) {
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
            flush()
        }

        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        webView.isVerticalScrollBarEnabled = false
        webView.isHorizontalScrollBarEnabled = false

        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            userAgentString = USER_AGENT

            // ★ BUG 68: file:// com JS habilitado + mixed content liberado era
            // risco desnecessário (o app só carrega domínios FEI/Moodle).
            allowFileAccess = false
            allowContentAccess = true
            mixedContentMode =
                WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                safeBrowsingEnabled = true
            }

            // window.open() só funciona porque o WebChromeClient implementa onCreateWindow
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = true
        }

        disableLongPress(webView)
    }

    fun disableTransitions(webView: WebView) {
        webView.animate()?.cancel()
        webView.alpha = 1f
        webView.visibility = View.VISIBLE
    }

    private fun disableLongPress(webView: WebView) {
        webView.setOnLongClickListener { true }
        webView.isLongClickable = false
        webView.isHapticFeedbackEnabled = false
    }

    fun setupDownloadListener(webView: WebView, context: Context, showToast: Boolean = false) {
        webView.setDownloadListener { url, userAgent, contentDisposition, mimetype, _ ->
            // ★ BUG 14: blob:/data: não são suportados pelo DownloadManager.
            if (url.startsWith("blob:") || url.startsWith("data:")) {
                Toast.makeText(
                    context, context.getString(R.string.download_nao_suportado), Toast.LENGTH_LONG
                ).show()
                return@setDownloadListener
            }

            try {
                val request = DownloadManager.Request(url.toUri())
                request.setMimeType(mimetype)

                // ★ BUG 14: getCookie() retorna null sem sessão → addRequestHeader(null)
                // lançava NPE e NENHUM download funcionava.
                val cookies = CookieManager.getInstance().getCookie(url)
                if (!cookies.isNullOrEmpty()) {
                    request.addRequestHeader("cookie", cookies)
                }
                if (!userAgent.isNullOrEmpty()) {
                    request.addRequestHeader("User-Agent", userAgent)
                }

                request.setDescription(context.getString(R.string.baixando_arquivo))
                val fileName = URLUtil.guessFileName(url, contentDisposition, mimetype)
                request.setTitle(fileName)
                request.setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                )
                request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)

                val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                dm.enqueue(request)

                if (showToast) {
                    Toast.makeText(
                        context, context.getString(R.string.download_iniciado), Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (_: Exception) {
                Toast.makeText(
                    context, context.getString(R.string.erro_ao_iniciar_download), Toast.LENGTH_SHORT
                ).show()
            }
        }
    }
}