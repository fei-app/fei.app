package com.marinov.openfei.data

import android.content.Context
import com.marinov.openfei.util.WebViewHelper

/**
 * Facade que delega para os repositórios modularizados.
 * Mantém a API pública original para compatibilidade.
 */
object Dados {
    private lateinit var appContext: Context

    fun init(context: Context) {
        appContext = context.applicationContext
        WebViewHelper.ensureWebView(appContext)
        CacheHelper.init(context)
        SessionManager.init(context)
        CalendarioRepository.init(context)
        BoletosRepository.init(context)
    }
    suspend fun atualizarNotas(online: Boolean) = NotasRepository.atualizarNotas(online)

    // ===================== AULAS =====================
    suspend fun aulas(online: Boolean) = AulasRepository.aulas(online)
    suspend fun novoHorario(online: Boolean) = AulasRepository.novoHorario(online)

    suspend fun atualizaBoletos() = BoletosRepository.atualizaBoletos()

    // ===================== CACHE =====================
    fun clearAllCacheFiles() = CacheHelper.clearAllCacheFiles()
}