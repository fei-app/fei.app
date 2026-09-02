package com.marinov.openfei.app

import android.app.Application
import android.os.Build
import com.google.android.material.color.DynamicColors
import com.marinov.openfei.util.WebViewHelper

class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // ★ Inicializa o estado do Modo Responsável Financeiro o quanto antes ★
        AppMode.init(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            DynamicColors.applyToActivitiesIfAvailable(this)
        }
        // ★ Garante que o WebView exista desde o início para o CookieManager funcionar ★
        WebViewHelper.ensureWebView(this)
    }
}