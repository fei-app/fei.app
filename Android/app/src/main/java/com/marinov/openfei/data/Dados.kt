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

    // ===================== SESSÃO =====================
    suspend fun garantirSessaoValida() = SessionManager.garantirSessaoValida()

    // ===================== DISCIPLINAS =====================
    suspend fun obterDisciplinas(online: Boolean) = DisciplinasRepository.obterDisciplinas(online)

    // ===================== NOTAS =====================
    suspend fun obterNotas(online: Boolean) = NotasRepository.obterNotas(online)
    suspend fun obterMedias(online: Boolean) = NotasRepository.obterMedias(online)
    suspend fun atualizarNotas(online: Boolean) = NotasRepository.atualizarNotas(online)
    fun ordenarNotasParaHome(notas: List<Nota>, provas: List<ProvaCalendario>) =
        NotasRepository.ordenarNotasParaHome(notas, provas)

    // ===================== PERFIL =====================
    suspend fun retornaDadosUsuario(online: Boolean) = PerfilRepository.retornaDadosUsuario(online)

    // ===================== AULAS =====================
    suspend fun aulas(online: Boolean) = AulasRepository.aulas(online)
    suspend fun novoHorario(online: Boolean) = AulasRepository.novoHorario(online)
    suspend fun retornaAulasDia(online: Boolean) = AulasRepository.retornaAulasDia(online)

    // ===================== CALENDÁRIO =====================
    suspend fun obterProvasFEI(online: Boolean) = CalendarioRepository.obterProvasFEI(online)
    suspend fun obterEventosMoodle(online: Boolean) = CalendarioRepository.obterEventosMoodle(online)
    suspend fun obterCalendarioProvas(online: Boolean) = CalendarioRepository.obterCalendarioProvas(online)
    fun obterCalendarioProvasCache() = CalendarioRepository.obterCalendarioProvasCache()
    fun obterProvasFEICache() = CalendarioRepository.obterProvasFEICache()
    fun obterEventosMoodleCache() = CalendarioRepository.obterEventosMoodleCache()

    // ===================== BOLETOS =====================
    suspend fun getBoletos(online: Boolean) = BoletosRepository.getBoletos(online)
    suspend fun atualizaBoletos() = BoletosRepository.atualizaBoletos()
    suspend fun baixaBoleto(tituloId: String, vencimento: String) = BoletosRepository.baixaBoleto(tituloId, vencimento)

    // ===================== CACHE =====================
    fun clearAllCacheFiles() = CacheHelper.clearAllCacheFiles()
}