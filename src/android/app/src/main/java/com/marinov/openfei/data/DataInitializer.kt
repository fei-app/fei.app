package com.marinov.openfei.data

import android.content.Context
import com.marinov.openfei.background.SyncPrefs
import com.marinov.openfei.cache.CacheHelper
import com.marinov.openfei.login.RustSession
import com.marinov.openfei.login.SessionManager
import com.marinov.openfei.util.WebViewHelper

/**
 * Ponto único de inicialização da camada de dados.
 * Substitui o antigo Dados.init() — o facade Dados.kt foi removido e cada
 * caller agora usa os repositórios diretamente.
 *
 * Idempotente: pode ser chamado pela Application, Activities e pelo Service.
 */
object DataInitializer {

    fun init(context: Context) {
        val appContext = context.applicationContext
        WebViewHelper.ensureWebView(appContext)
        CacheHelper.init(appContext)
        SessionManager.init(appContext)
        CalendarioRepository.init(appContext)
        BoletosRepository.init(appContext)
        RustSession.init(appContext)
        SyncPrefs.init(appContext)
    }
}