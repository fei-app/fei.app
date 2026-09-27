package com.marinov.openfei.data

import android.content.Context
import android.util.Log
import androidx.core.content.FileProvider
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
                val boletos = fetchBoletosFromRust()
                CacheHelper.saveBoletosCache(boletos)
                boletos
            } catch (e: SessionExpiredException) {
                throw e
            } catch (e: Exception) {
                if (e !is CancellationException) {
                    Log.e(TAG, "Erro ao buscar boletos online", e)
                }
                CacheHelper.getCachedBoletos()
            }
        } else {
            CacheHelper.getCachedBoletos()
        }
    }

    suspend fun atualizaBoletos(): Boolean {
        return try {
            val novos = fetchBoletosFromRust()
            val antigos = CacheHelper.getCachedBoletos()
            if (antigos.isEmpty()) {
                CacheHelper.saveBoletosCache(novos)
                return false
            }
            val alterado = novos.size != antigos.size ||
                    novos.zip(antigos).any { (novo, antigo) ->
                        novo.vencimento != antigo.vencimento ||
                                novo.status != antigo.status ||
                                novo.dataPagamento != antigo.dataPagamento
                    }
            if (alterado) CacheHelper.saveBoletosCache(novos)
            alterado
        } catch (e: SessionExpiredException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Erro em atualizaBoletos", e)
            false
        }
    }

    suspend fun baixaBoleto(tituloId: String, vencimento: String): android.net.Uri? = withContext(Dispatchers.IO) {
        try {
            // ★ CORREÇÃO: Garante sessão FEI antes de baixar boleto
            if (!RustSession.ensureFeiSession()) {
                Log.e(TAG, "Não foi possível garantir sessão FEI para baixar boleto")
                return@withContext null
            }

            val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOWNLOADS
            )
            val boletoDir = File(downloadsDir, "BoletosFEI").also { it.mkdirs() }

            when (val result = OpenFeiCore.downloadBoleto(tituloId, vencimento, boletoDir.absolutePath)) {
                is CoreResult.Success -> {
                    val outputFile = File(result.data.path)
                    android.media.MediaScannerConnection.scanFile(
                        appContext,
                        arrayOf(outputFile.absolutePath),
                        arrayOf("application/pdf"),
                        null
                    )
                    FileProvider.getUriForFile(
                        appContext,
                        "${appContext.packageName}.fileprovider",
                        outputFile
                    )
                }
                is CoreResult.Error -> {
                    Log.e(TAG, "Erro ao baixar boleto: ${result.code} - ${result.message}")
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao baixar boleto $tituloId", e)
            null
        }
    }

    private suspend fun fetchBoletosFromRust(): List<Boleto> = withContext(Dispatchers.IO) {
        // ★ CORREÇÃO: Garante sessão FEI antes de buscar boletos
        if (!RustSession.ensureFeiSession()) {
            throw SessionExpiredException("Não foi possível garantir sessão FEI para boletos")
        }

        when (val result = OpenFeiCore.fetchBoletos()) {
            is CoreResult.Success -> {
                result.data.map { core ->
                    Boleto(
                        vencimento = core.vencimento,
                        status = core.status,
                        dataPagamento = core.dataPagamento,
                        tituloId = core.tituloId
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
}