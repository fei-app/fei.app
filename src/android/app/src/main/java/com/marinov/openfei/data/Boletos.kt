package com.marinov.openfei.data

import android.content.Context
import android.net.Uri
import android.util.Log
import com.marinov.openfei.cache.CacheHelper
import com.marinov.openfei.cache.SyncBaseline
import com.marinov.openfei.core.CoreResult
import com.marinov.openfei.core.OpenFeiCore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

object BoletosRepository {
    private const val TAG = "BoletosRepository"
    private lateinit var appContext: Context

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    suspend fun getBoletos(online: Boolean): List<Boleto> {
        return if (online) {
            try {
                persistirBoletos(fetchBoletosFromRust())
            } catch (e: SessionExpiredException) {
                throw e
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Erro ao buscar boletos online", e)
                CacheHelper.getCachedBoletos()
            }
        } else {
            CacheHelper.getCachedBoletos()
        }
    }

    /**
     * ★ BUG 80: true SOMENTE quando houve mudança real entre dois snapshots
     * válidos. Nunca na primeira execução, nunca com dados nulos.
     */
    suspend fun atualizaBoletos(): Boolean {
        return try {
            if (!::appContext.isInitialized) {
                Log.w(TAG, "BoletosRepository sem contexto — sync sem notificação")
                return false
            }

            // Linhas totalmente vazias (parse parcial) são descartadas.
            val novos = fetchBoletosFromRust()
                .filter { it.tituloId.isNotBlank() || it.vencimento.isNotBlank() }

            // 1) Dados nulos do servidor.
            if (novos.isEmpty()) {
                Log.w(TAG, "Boletos: servidor devolveu 0 registros válidos — nada a comparar")
                return false
            }

            val antigos = CacheHelper.getCachedBoletos()

            // 2) Primeira execução (ou cache perdido): semeia em silêncio.
            if (!SyncBaseline.isEstablished(appContext, SyncBaseline.BOLETOS) || antigos.isEmpty()) {
                Log.i(TAG, "Boletos: primeira execução — semeando ${novos.size} registros SEM notificar")
                CacheHelper.saveBoletosCache(novos)
                SyncBaseline.markEstablished(appContext, SyncBaseline.BOLETOS)
                return false
            }

            // 3) Comparação real por identidade estável (tituloId pode vir vazio).
            val mapaAntigos = antigos.associateBy { chaveBoleto(it) }
            val mapaNovos = novos.associateBy { chaveBoleto(it) }

            val alterado = mapaNovos.keys != mapaAntigos.keys ||
                    mapaNovos.any { (chave, novo) ->
                        val antigo = mapaAntigos[chave] ?: return@any true
                        novo != antigo
                    }

            if (alterado) CacheHelper.saveBoletosCache(novos)
            alterado
        } catch (e: SessionExpiredException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Erro em atualizaBoletos", e)
            false
        }
    }

    /**
     * ★ BUG 80: `associateBy { tituloId }` colapsava boletos com id vazio e
     * gerava "boleto novo" eterno. Fallback estável sem o status (para que
     * mudança de status continue sendo detectada como alteração).
     */
    private fun chaveBoleto(boleto: Boleto): String =
        boleto.tituloId.ifBlank { "${boleto.vencimento}|${boleto.dataPagamento}" }

    /** ★ BUG 80: vazio nunca destrói cache válido. */
    private fun persistirBoletos(novos: List<Boleto>): List<Boleto> {
        val cache = CacheHelper.getCachedBoletos()
        if (novos.isEmpty() && cache.isNotEmpty()) {
            Log.w(TAG, "Boletos vazios online — mantendo cache (${cache.size})")
            return cache
        }
        CacheHelper.saveBoletosCache(novos)
        return novos
    }

    /**
     * Rust grava o PDF no cache interno do app (sempre permitido) e depois o publicamos em
     * Download/OpenFEI/Boletos via [DownloadsPublisher]. Se a publicação falhar (p.ex. API <= 28
     * sem permissão), devolvemos uma Uri do arquivo em cache para o PDF ainda poder ser aberto.
     */
    suspend fun baixaBoleto(tituloId: String, vencimento: String): Uri? = withContext(Dispatchers.IO) {
        try {
            ensureFeiSessionOrThrow("baixar boleto")
            val cacheDir = File(appContext.cacheDir, "boletos").apply { mkdirs() }
            when (val result = OpenFeiCore.downloadBoleto(tituloId, vencimento, cacheDir.absolutePath)) {
                is CoreResult.Success -> {
                    val srcFile = File(result.data.path)
                    if (!srcFile.exists()) return@withContext null

                    DownloadsPublisher.publicar(
                        appContext, srcFile, srcFile.name,
                        "application/pdf", DownloadsPublisher.SUB_BOLETOS
                    ) ?: DownloadsPublisher.uriFileProvider(appContext, srcFile)
                }
                is CoreResult.Error -> {
                    Log.e(TAG, "Erro ao baixar boleto: ${result.code} - ${result.message}")
                    null
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao baixar boleto $tituloId", e)
            null
        }
    }

    private suspend fun fetchBoletosFromRust(): List<Boleto> = withContext(Dispatchers.IO) {
        ensureFeiSessionOrThrow("boletos")
        when (val result = OpenFeiCore.fetchBoletos()) {
            is CoreResult.Success -> result.data.map { core ->
                Boleto(
                    vencimento = core.vencimento,
                    status = core.status,
                    dataPagamento = core.dataPagamento,
                    tituloId = core.tituloId
                )
            }
            is CoreResult.Error -> {
                if (result.code == "SESSION_EXPIRED") throw SessionExpiredException(result.message)
                throw IOException(result.message)
            }
        }
    }
}