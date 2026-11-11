package com.marinov.openfei.data

import android.content.Context
import com.marinov.openfei.background.SyncPrefs
import com.marinov.openfei.cache.CacheHelper
import com.marinov.openfei.login.RustSession
import com.marinov.openfei.login.SessionManager
import com.marinov.openfei.network.NetworkChecker
import com.marinov.openfei.util.WebViewHelper

/**
 * Ponto único de inicialização da camada de dados.
 * Idempotente: pode ser chamado pela Application, Activities e pelo Worker.
 */
object DataInitializer {
    fun init(context: Context) {
        val appContext = context.applicationContext
        WebViewHelper.ensureWebView(appContext)
        CacheHelper.init(appContext)
        // ★ BUG 80: sem init o fallback de conectividade do sistema nunca funcionava.
        NetworkChecker.init(appContext)
        SessionManager.init(appContext)
        CalendarioRepository.init(appContext)
        BoletosRepository.init(appContext)
        // ★ BUG 80: repositórios que notificam precisam de contexto para o baseline.
        NotasRepository.init(appContext)
        HorariosRepository.init(appContext)
        RustSession.init(appContext)
        SyncPrefs.init(appContext)
    }
}