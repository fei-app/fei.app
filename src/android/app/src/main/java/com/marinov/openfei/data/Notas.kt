package com.marinov.openfei.data

import android.util.Log
import com.marinov.openfei.core.CoreResult
import com.marinov.openfei.core.OpenFeiCore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

object NotasRepository {

    private const val TAG = "NotasRepository"

    suspend fun obterNotas(online: Boolean): List<Nota> {
        return if (online) {
            try {
                val notas = fetchNotasFromRust()
                CacheHelper.saveNotasCache(notas)
                notas
            } catch (e: SessionExpiredException) {
                throw e
            } catch (e: Exception) {
                if (e !is CancellationException) {
                    Log.e(TAG, "Erro ao buscar notas online", e)
                }
                CacheHelper.getCachedNotas()
            }
        } else {
            CacheHelper.getCachedNotas()
        }
    }

    suspend fun obterMedias(online: Boolean): Map<String, String> {
        return if (online) {
            try {
                val medias = fetchMediasFromRust()
                CacheHelper.saveMediasCache(medias)
                medias
            } catch (e: SessionExpiredException) {
                throw e
            } catch (e: Exception) {
                if (e !is CancellationException) {
                    Log.e(TAG, "Erro ao buscar médias online", e)
                }
                CacheHelper.getCachedMedias()
            }
        } else {
            CacheHelper.getCachedMedias()
        }
    }

    suspend fun atualizarNotas(online: Boolean): List<Nota> {
        if (!online) return emptyList()
        try {
            val novasNotas = fetchNotasFromRust()
            val antigasNotas = CacheHelper.getCachedNotas()
            if (antigasNotas.isEmpty()) {
                CacheHelper.saveNotasCache(novasNotas)
                return emptyList()
            }
            val mapaAntigas = antigasNotas.associateBy { "${it.codigoDisciplina}|${it.tipoProva}" }
            val notasAlteradas = novasNotas.filter { nova ->
                val chave = "${nova.codigoDisciplina}|${nova.tipoProva}"
                mapaAntigas[chave]?.valor != nova.valor
            }
            CacheHelper.saveNotasCache(novasNotas)
            return notasAlteradas
        } catch (e: SessionExpiredException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Erro em atualizarNotas", e)
            return emptyList()
        }
    }

    private suspend fun fetchNotasFromRust(): List<Nota> = withContext(Dispatchers.IO) {
        if (!RustSession.ensureFeiSession()) {
            throw SessionExpiredException("Não foi possível garantir sessão FEI para notas")
        }
        when (val result = OpenFeiCore.fetchNotas()) {
            is CoreResult.Success -> {
                result.data.map { core ->
                    Nota(
                        codigoDisciplina = core.codigoDisciplina,
                        nomeDisciplina = core.nomeDisciplina,
                        tipoProva = core.tipoProva,
                        valor = core.valor
                    )
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

    private suspend fun fetchMediasFromRust(): Map<String, String> = withContext(Dispatchers.IO) {
        if (!RustSession.ensureFeiSession()) {
            throw SessionExpiredException("Não foi possível garantir sessão FEI para médias")
        }
        when (val result = OpenFeiCore.fetchMedias()) {
            is CoreResult.Success -> result.data
            is CoreResult.Error -> {
                if (result.code == "SESSION_EXPIRED") {
                    throw SessionExpiredException(result.message)
                }
                throw IOException(result.message)
            }
        }
    }
}