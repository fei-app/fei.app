package com.marinov.openfei.data

import android.util.Log
import com.marinov.openfei.cache.CacheHelper
import com.marinov.openfei.core.CoreResult
import com.marinov.openfei.core.OpenFeiCore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

object DisciplinasRepository {
    private const val TAG = "DisciplinasRepository"

    suspend fun obterDisciplinas(online: Boolean): List<Disciplina> {
        return if (online) {
            try {
                persistirDisciplinas(fetchDisciplinasFromRust())
            } catch (e: SessionExpiredException) {
                throw e
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Erro ao buscar disciplinas online", e)
                CacheHelper.getCachedDisciplinas()
            }
        } else {
            CacheHelper.getCachedDisciplinas()
        }
    }

    /**
     * ★ BUG 80: lista vazia nunca apaga o cache — sem disciplinas os nomes
     * das aulas deixam de ser resolvidos e o sync de horário dispara falso
     * "novo horário" na execução seguinte.
     */
    private fun persistirDisciplinas(novas: List<Disciplina>): List<Disciplina> {
        val cache = CacheHelper.getCachedDisciplinas()
        if (novas.isEmpty() && cache.isNotEmpty()) {
            Log.w(TAG, "Disciplinas vazias online — mantendo cache (${cache.size})")
            return cache
        }
        CacheHelper.saveDisciplinasCache(novas)
        return novas
    }

    private suspend fun fetchDisciplinasFromRust(): List<Disciplina> = withContext(Dispatchers.IO) {
        ensureFeiSessionOrThrow("disciplinas")
        when (val result = OpenFeiCore.fetchDisciplinas()) {
            is CoreResult.Success -> {
                result.data.map { core ->
                    Disciplina(codigo = core.codigo, nome = core.nome)
                }
            }
            is CoreResult.Error -> {
                if (result.code == "SESSION_EXPIRED") {
                    throw SessionExpiredException(result.message)
                }
                throw IOException(result.message)
            }
        }
    }
}