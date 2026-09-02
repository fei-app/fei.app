package com.marinov.openfei.data

import android.util.Log
import kotlinx.coroutines.CancellationException

object DisciplinasRepository {
    private const val TAG = "DisciplinasRepository"
    private const val URL_DISCIPLINAS = "https://interage.fei.org.br/secureserver/portal/graduacao/sala-dos-professores/consultas/tabela-de-aulas"

    suspend fun obterDisciplinas(online: Boolean): List<Disciplina> {
        return if (online) {
            try {
                val disciplinas = fetchDisciplinasFromServer()
                CacheHelper.saveDisciplinasCache(disciplinas)
                disciplinas
            } catch (e: SessionExpiredException) { throw e }
            catch (e: Exception) {
                if (e !is CancellationException) Log.e(TAG, "Erro ao buscar disciplinas online", e)
                CacheHelper.getCachedDisciplinas()
            }
        } else { CacheHelper.getCachedDisciplinas() }
    }

    private suspend fun fetchDisciplinasFromServer(): List<Disciplina> {
        val doc = SessionManager.fetchPage(URL_DISCIPLINAS)
        val container = doc.selectFirst("body > div.container > div:nth-child(2) > div.col-md-9 > div:nth-child(2)")
            ?: throw SessionExpiredException("Container de disciplinas não encontrado")

        val tabela = container.selectFirst("table.table.table-striped")
            ?: throw SessionExpiredException("Tabela de disciplinas não encontrada")

        val disciplinas = mutableListOf<Disciplina>()
        val linhas = tabela.select("tbody > tr")

        for (linha in linhas) {
            val codigoElement = linha.selectFirst("td.Código")
            val nomeElement = linha.selectFirst("td.Disciplina")
            if (codigoElement != null && nomeElement != null) {
                val codigo = codigoElement.text().trim()
                val nome = nomeElement.text().trim()
                if (codigo.isNotEmpty() && nome.isNotEmpty()) {
                    disciplinas.add(Disciplina(codigo, nome))
                }
            }
        }
        return disciplinas
    }
}