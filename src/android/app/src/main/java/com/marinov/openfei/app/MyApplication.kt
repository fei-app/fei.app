package com.marinov.openfei.app

import android.app.Application
import android.os.Build
import com.google.android.material.color.DynamicColors
import com.marinov.openfei.background.Notifications
import com.marinov.openfei.background.SyncScheduler
import com.marinov.openfei.data.DataInitializer
import com.marinov.openfei.util.WebViewHelper

class MyApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        AppMode.init(this)
        DataInitializer.init(this)
        Notifications.ensureChannels(this)

        // ★ BUG 64: removido o bloco Thread { RUST_TEST }.
        // Ele chamava fetchDisciplinas() sem sessão garantida a cada start,
        // gerava log de erro e carregava a lib nativa antes do necessário.

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            DynamicColors.applyToActivitiesIfAvailable(this)
        }

        // Garante o WebView (para o CookieManager) e os agendamentos de sync.
        WebViewHelper.ensureWebView(this)
        SyncScheduler.scheduleAll(this)
    }
}