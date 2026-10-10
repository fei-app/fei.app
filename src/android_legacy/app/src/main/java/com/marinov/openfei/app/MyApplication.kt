package com.marinov.openfei.app

import android.app.Application
import android.os.Build
import com.google.android.material.color.DynamicColors
import com.marinov.openfei.core.OpenFeiCore
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
        Thread {
            try {
                val isOnline = OpenFeiCore.isOnline()
                android.util.Log.d("RUST_TEST", "isOnline: $isOnline")

                val disciplinas = OpenFeiCore.fetchDisciplinas()
                android.util.Log.d("RUST_TEST", "disciplinas: $disciplinas")
            } catch (e: Throwable) {
                android.util.Log.e("RUST_TEST", "Erro", e)
            }
        }.start()
    }
}