package com.marinov.openfei.data

import android.content.Context
import android.util.Log
import com.marinov.openfei.cache.CacheHelper
import com.marinov.openfei.cache.SyncBaseline
import com.marinov.openfei.core.CoreResult
import com.marinov.openfei.core.OpenFeiCore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

object NotasRepository {
    private const val TAG = "NotasRepository"

    /** ★ BUG 80: contexto para o marco de primeira execução. */
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    suspend fun obterNotas(online: Boolean): List<Nota> {
        return if (online) {
            try {
                persistirNotas(fetchNotasFromRust())
            } catch (e: SessionExpiredException) {
                throw e
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Erro ao buscar notas online", e)
                CacheHelper.getCachedNotas()
            }
        } else {
            CacheHelper.getCachedNotas()
        }
    }

    suspend fun obterMedias(online: Boolean): Map<String, String> {
        return if (online) {
            try {
                persistirMedias(fetchMediasFromRust())
            } catch (e: SessionExpiredException) {
                throw e
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Erro ao buscar médias online", e)
                CacheHelper.getCachedMedias()
            }
        } else {
            CacheHelper.getCachedMedias()
        }
    }

    /**
     * ★ BUG 80: devolve SOMENTE notas realmente novas/alteradas.
     *
     * Nunca notifica quando:
     *  - o repositório não foi inicializado;
     *  - o servidor devolveu lista vazia (dados nulos);
     *  - não existe baseline (primeira execução / instalação limpa);
     *  - o cache está vazio (perdido, corrompido ou limpo pelo usuário);
     *  - o "valor" da nota é vazio (linha publicada sem nota).
     */
    suspend fun atualizarNotas(online: Boolean): List<Nota> {
        if (!online) return emptyList()
        try {
            val ctx = appContext
            if (ctx == null) {
                Log.w(TAG, "NotasRepository sem contexto — sync sem notificação")
                return emptyList()
            }

            val novasNotas = fetchNotasFromRust()

            // 1) Dados nulos do servidor: não notifica e não destrói o cache.
            if (novasNotas.isEmpty()) {
                Log.w(TAG, "Notas: servidor devolveu 0 registros — nada a comparar")
                return emptyList()
            }

            val antigasNotas = CacheHelper.getCachedNotas()

            // 2) Primeira execução (ou cache perdido): semeia em silêncio.
            if (!SyncBaseline.isEstablished(ctx, SyncBaseline.NOTAS) || antigasNotas.isEmpty()) {
                Log.i(TAG, "Notas: primeira execução — semeando ${novasNotas.size} registros SEM notificar")
                CacheHelper.saveNotasCache(novasNotas)
                SyncBaseline.markEstablished(ctx, SyncBaseline.NOTAS)
                return emptyList()
            }

            // 3) Comparação real, com baseline válido dos dois lados.
            val mapaAntigas = antigasNotas.associateBy { chave(it) }
            val notasAlteradas = novasNotas.filter { nova ->
                nova.valor.isNotBlank() && mapaAntigas[chave(nova)]?.valor != nova.valor
            }
            CacheHelper.saveNotasCache(novasNotas)
            return notasAlteradas
        } catch (e: SessionExpiredException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Erro em atualizarNotas", e)
            return emptyList()
        }
    }

    private fun chave(nota: Nota): String = "${nota.codigoDisciplina}|${nota.tipoProva}"

    /**
     * ★ BUG 80: resultado vazio/nulo NUNCA sobrescreve um cache válido.
     * Devolve a lista que deve ser exibida.
     */
    private fun persistirNotas(novas: List<Nota>): List<Nota> {
        val cache = CacheHelper.getCachedNotas()
        if (novas.isEmpty() && cache.isNotEmpty()) {
            Log.w(TAG, "Notas vazias online — mantendo cache (${cache.size})")
            return cache
        }
        CacheHelper.saveNotasCache(novas)
        return novas
    }

    private fun persistirMedias(novas: Map<String, String>): Map<String, String> {
        val cache = CacheHelper.getCachedMedias()
        if (novas.isEmpty() && cache.isNotEmpty()) {
            Log.w(TAG, "Médias vazias online — mantendo cache (${cache.size})")
            return cache
        }
        CacheHelper.saveMediasCache(novas)
        return novas
    }

    private suspend fun fetchNotasFromRust(): List<Nota> = withContext(Dispatchers.IO) {
        ensureFeiSessionOrThrow("notas")
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
        ensureFeiSessionOrThrow("médias")
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