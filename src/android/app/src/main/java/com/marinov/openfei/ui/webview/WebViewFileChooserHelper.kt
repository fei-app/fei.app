package com.marinov.openfei.ui.webview

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Message
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.result.ActivityResultCaller
import androidx.activity.result.contract.ActivityResultContracts
import com.marinov.openfei.R

class WebViewFileChooserHelper(
    caller: ActivityResultCaller,
    private val contextProvider: () -> Context
) {
    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    private val launcher = caller.registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val callback = filePathCallback
        filePathCallback = null

        if (callback == null) return@registerForActivityResult

        val results: Array<Uri>? = if (result.resultCode == Activity.RESULT_OK) {
            val data = result.data
            when {
                data?.data != null -> arrayOf(data.data!!)
                data?.clipData != null -> {
                    val count = data.clipData!!.itemCount
                    Array(count) { i -> data.clipData!!.getItemAt(i).uri }
                }
                else -> null
            }
        } else null

        callback.onReceiveValue(results)
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun attachTo(webView: WebView) {
        webView.webChromeClient = object : WebChromeClient() {

            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                // Libera callback anterior pendurado
                this@WebViewFileChooserHelper.filePathCallback?.onReceiveValue(null)
                this@WebViewFileChooserHelper.filePathCallback = filePathCallback

                val intent = fileChooserParams?.createIntent()
                if (intent == null) {
                    // ★ BUG 15: SEMPRE resolver o callback, senão o chooser do WebView
                    // trava para sempre e nenhum upload volta a funcionar.
                    this@WebViewFileChooserHelper.filePathCallback = null
                    filePathCallback?.onReceiveValue(null)
                    return false
                }

                return try {
                    launcher.launch(intent)
                    true
                } catch (_: ActivityNotFoundException) {
                    this@WebViewFileChooserHelper.filePathCallback = null
                    filePathCallback?.onReceiveValue(null)
                    val context = webView?.context ?: contextProvider()
                    Toast.makeText(
                        context,
                        context.getString(R.string.nenhum_app_para_arquivos),
                        Toast.LENGTH_LONG
                    ).show()
                    false
                }
            }

            /** ★ BUG 27: setSupportMultipleWindows(true) exige onCreateWindow,
             *  senão todo window.open() do Moodle morre em silêncio. */
            override fun onCreateWindow(
                view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?
            ): Boolean {
                val host = view ?: return false
                if (!isUserGesture) return false

                val transportHolder = resultMsg?.obj as? WebView.WebViewTransport ?: return false
                val transport = WebView(host.context)
                transport.settings.javaScriptEnabled = true

                transport.webViewClient = object : WebViewClient() {
                    override fun onPageStarted(v: WebView, url: String?, favicon: Bitmap?) {
                        if (!url.isNullOrEmpty()) host.loadUrl(url)
                        v.stopLoading()
                        host.post { runCatching { v.destroy() } }
                    }
                }

                transportHolder.webView = transport
                resultMsg.sendToTarget()
                return true
            }
        }
    }
}