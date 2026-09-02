package com.marinov.openfei.data

import android.content.Context
import android.util.Log
import androidx.core.content.FileProvider
import com.marinov.openfei.util.WebViewHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Connection
import org.jsoup.Jsoup
import java.io.File

object BoletosRepository {
    private const val TAG = "BoletosRepository"
    private const val URL_BOLETOS = "https://interage.fei.org.br/secureserver/portal/graduacao/tesouraria/consultas/boletos"
    private const val URL_GERAR_BOLETO = "https://interage.fei.org.br/secureserver/portal/graduacao/tesouraria/consultas/boletos/titulos/gerar"
    private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 16; sdk_gphone64_x86_64 Build/BE2A.250530.026.D1; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/133.0.6943.137 Mobile Safari/537.36"

    private lateinit var appContext: Context

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    suspend fun getBoletos(online: Boolean): List<Boleto> {
        return if (online) {
            try {
                val boletos = fetchBoletosFromServer()
                CacheHelper.saveBoletosCache(boletos)
                boletos
            } catch (e: SessionExpiredException) { throw e }
            catch (e: Exception) {
                if (e !is CancellationException) Log.e(TAG, "Erro ao buscar boletos online", e)
                CacheHelper.getCachedBoletos()
            }
        } else { CacheHelper.getCachedBoletos() }
    }

    suspend fun atualizaBoletos(): Boolean {
        return try {
            val novos = fetchBoletosFromServer()
            val antigos = CacheHelper.getCachedBoletos()

            if (antigos.isEmpty()) { CacheHelper.saveBoletosCache(novos); return false }

            val alterado = novos.size != antigos.size ||
                    novos.zip(antigos).any { (novo, antigo) ->
                        novo.vencimento != antigo.vencimento ||
                                novo.status != antigo.status ||
                                novo.dataPagamento != antigo.dataPagamento
                    }

            if (alterado) CacheHelper.saveBoletosCache(novos)
            alterado
        } catch (e: SessionExpiredException) { throw e }
        catch (e: Exception) {
            Log.e(TAG, "Erro em atualizaBoletos", e)
            false
        }
    }

    suspend fun baixaBoleto(tituloId: String, vencimento: String): android.net.Uri? = withContext(Dispatchers.IO) {
        try {
            val partes = vencimento.split("/")
            val nomeArquivo = if (partes.size == 3) "${partes[2]}_${partes[1]}.pdf" else "$tituloId.pdf"

            SessionManager.renewSession()

            val webViewCookies = WebViewHelper.getCookiesSafely(URL_BOLETOS)
            val getResponse = Jsoup.connect(URL_BOLETOS)
                .userAgent(USER_AGENT)
                .header("Cookie", webViewCookies)
                .timeout(20_000)
                .ignoreContentType(true)
                .ignoreHttpErrors(true)
                .method(Connection.Method.GET)
                .execute()

            val pageDoc = getResponse.parse()
            val csrfToken = pageDoc
                .selectFirst("#form-gerar-boletos input[name=__RequestVerificationToken]")
                ?.`val`()
                ?: run {
                    Log.e(TAG, "CSRF token não encontrado na página de boletos")
                    return@withContext null
                }

            val responseCookies = getResponse.cookies()
            val cookiesMerged = buildString {
                append(webViewCookies)
                for ((name, value) in responseCookies) {
                    if (isNotEmpty()) append("; ")
                    append("$name=$value")
                }
            }

            val postResponse = Jsoup.connect(URL_GERAR_BOLETO)
                .userAgent(USER_AGENT)
                .header("Cookie", cookiesMerged)
                .header("Referer", URL_BOLETOS)
                .header("Accept", "application/pdf,text/html,*/*")
                .data("__RequestVerificationToken", csrfToken)
                .data("respFinanceiro", "0")
                .data("titulos", tituloId)
                .method(Connection.Method.POST)
                .ignoreContentType(true)
                .ignoreHttpErrors(true)
                .timeout(30_000)
                .maxBodySize(10485760)
                .execute()

            val contentType = postResponse.contentType() ?: ""
            if (!contentType.contains("pdf", ignoreCase = true)) {
                Log.e(TAG, "Resposta não é PDF (Content-Type=$contentType)")
                return@withContext null
            }

            val pdfBytes = postResponse.bodyAsBytes()
            if (pdfBytes.size < 1000) {
                Log.e(TAG, "PDF suspeito: apenas ${pdfBytes.size} bytes")
                return@withContext null
            }

            val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOWNLOADS
            )
            val boletoDir = File(downloadsDir, "BoletosFEI").also { it.mkdirs() }
            val outputFile = File(boletoDir, nomeArquivo)
            outputFile.writeBytes(pdfBytes)

            android.media.MediaScannerConnection.scanFile(
                appContext, arrayOf(outputFile.absolutePath),
                arrayOf("application/pdf"), null
            )

            FileProvider.getUriForFile(appContext, "${appContext.packageName}.fileprovider", outputFile)
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao baixar boleto $tituloId", e)
            null
        }
    }

    // ===================== FETCH =====================
    private suspend fun fetchBoletosFromServer(): List<Boleto> {
        val doc = SessionManager.fetchPage(URL_BOLETOS)
        val form = doc.selectFirst("#form-gerar-boletos")
            ?: throw SessionExpiredException("Formulário de boletos não encontrado — sessão inválida")

        val tabela = form.selectFirst("table.table")
            ?: throw SessionExpiredException("Tabela de boletos não encontrada")

        val boletos = mutableListOf<Boleto>()
        val linhas = tabela.select("tbody > tr")

        for (linha in linhas) {
            val vencimento = linha.selectFirst("td[class*=Vencimento]")?.text()?.trim() ?: continue
            val status = linha.selectFirst("td[class*=Status]")?.text()?.trim() ?: continue
            val dataPagamento = linha.selectFirst("td[class*=Data]")?.text()?.trim() ?: ""
            val tituloId = linha.selectFirst("input[name=titulos]")?.`val`()?.trim() ?: ""

            if (vencimento.isNotEmpty() && status.isNotEmpty()) {
                boletos.add(Boleto(vencimento, status, dataPagamento, tituloId))
            }
        }
        return boletos
    }
}