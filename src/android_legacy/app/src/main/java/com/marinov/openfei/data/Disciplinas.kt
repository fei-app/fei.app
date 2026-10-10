package com.marinov.openfei.data

import android.util.Log
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
                val disciplinas = fetchDisciplinasFromRust()
                CacheHelper.saveDisciplinasCache(disciplinas)
                disciplinas
            } catch (e: SessionExpiredException) {
                throw e
            } catch (e: Exception) {
                if (e !is CancellationException) {
                    Log.e(TAG, "Erro ao buscar disciplinas online", e)
                }
                CacheHelper.getCachedDisciplinas()
            }
        } else {
            CacheHelper.getCachedDisciplinas()
        }
    }

    private suspend fun fetchDisciplinasFromRust(): List<Disciplina> = withContext(Dispatchers.IO) {
        // ★ CORREÇÃO: Garante sessão FEI antes de buscar disciplinas
        if (!RustSession.ensureFeiSession()) {
            throw SessionExpiredException("Não foi possível garantir sessão FEI para disciplinas")
        }

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