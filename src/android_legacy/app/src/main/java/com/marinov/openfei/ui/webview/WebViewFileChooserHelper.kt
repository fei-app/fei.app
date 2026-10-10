package com.marinov.openfei.ui.webview

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.net.Uri
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
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
        if (result.resultCode == Activity.RESULT_OK) {
            val data = result.data
            val results: Array<Uri>? = if (data?.data != null) {
                arrayOf(data.data!!)
            } else if (data?.clipData != null) {
                val count = data.clipData!!.itemCount
                Array(count) { i -> data.clipData!!.getItemAt(i).uri }
            } else {
                null
            }
            filePathCallback?.onReceiveValue(results)
        } else {
            filePathCallback?.onReceiveValue(null)
        }
        filePathCallback = null
    }

    fun attachTo(webView: WebView) {
        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                this@WebViewFileChooserHelper.filePathCallback?.onReceiveValue(null)
                this@WebViewFileChooserHelper.filePathCallback = filePathCallback

                val intent = fileChooserParams?.createIntent() ?: return false

                return try {
                    launcher.launch(intent)
                    true
                } catch (_: ActivityNotFoundException) {
                    this@WebViewFileChooserHelper.filePathCallback = null
                    val context = webView?.context ?: contextProvider()
                    Toast.makeText(
                        context,
                        context.getString(R.string.nenhum_app_para_arquivos),
                        Toast.LENGTH_LONG
                    ).show()
                    false
                }
            }
        }
    }
}