package com.marinov.openfei.data

import android.util.Log
import kotlinx.coroutines.CancellationException
import java.util.Calendar

object NotasRepository {
    private const val TAG = "NotasRepository"
    private const val URL_NOTAS = "https://interage.fei.org.br/secureserver/portal/graduacao/secretaria/consultas/notas"

    suspend fun obterNotas(online: Boolean): List<Nota> {
        return if (online) {
            try {
                val notas = fetchNotasFromServer()
                CacheHelper.saveNotasCache(notas)
                notas
            } catch (e: SessionExpiredException) { throw e }
            catch (e: Exception) {
                if (e !is CancellationException) Log.e(TAG, "Erro ao buscar notas online", e)
                CacheHelper.getCachedNotas()
            }
        } else { CacheHelper.getCachedNotas() }
    }

    suspend fun obterMedias(online: Boolean): Map<String, String> {
        return if (online) {
            try {
                val medias = fetchMediasFromServer()
                CacheHelper.saveMediasCache(medias)
                medias
            } catch (e: SessionExpiredException) { throw e }
            catch (e: Exception) {
                if (e !is CancellationException) Log.e(TAG, "Erro ao buscar médias online", e)
                CacheHelper.getCachedMedias()
            }
        } else { CacheHelper.getCachedMedias() }
    }

    suspend fun atualizarNotas(online: Boolean): List<Nota> {
        if (!online) return emptyList()
        try {
            val novasNotas = fetchNotasFromServer()
            val antigasNotas = CacheHelper.getCachedNotas()

            if (antigasNotas.isEmpty()) { CacheHelper.saveNotasCache(novasNotas); return emptyList() }

            val mapaAntigas = antigasNotas.associateBy { "${it.codigoDisciplina}|${it.tipoProva}" }
            val notasAlteradas = novasNotas.filter { nova ->
                val chave = "${nova.codigoDisciplina}|${nova.tipoProva}"
                mapaAntigas[chave]?.valor != nova.valor
            }
            CacheHelper.saveNotasCache(novasNotas)
            return notasAlteradas
        } catch (e: SessionExpiredException) { throw e }
        catch (e: Exception) {
            Log.e(TAG, "Erro em atualizarNotas", e)
            return emptyList()
        }
    }

    fun ordenarNotasParaHome(notas: List<Nota>, provas: List<ProvaCalendario>): List<Nota> {
        val tiposConhecidos = setOf("P1", "P2", "P3")
        fun normalizar(cod: String) = cod.trim().uppercase()

        fun dataParaInt(data: String): Int {
            val partes = data.split("/")
            if (partes.size < 2) return Int.MIN_VALUE
            val dia = partes[0].toIntOrNull() ?: return Int.MIN_VALUE
            val mes = partes[1].toIntOrNull() ?: return Int.MIN_VALUE
            return mes * 100 + dia
        }

        fun tipoPeso(tipo: String): Int = when (tipo) { "P3" -> 3; "P2" -> 2; "P1" -> 1; else -> 0 }

        val hoje = Calendar.getInstance()
        val hojeInt = (hoje.get(Calendar.MONTH) + 1) * 100 + hoje.get(Calendar.DAY_OF_MONTH)

        val disciplinasComP3 = notas.filter { it.tipoProva == "P3" && it.valor.isNotEmpty() }
            .map { normalizar(it.codigoDisciplina) }.toSet()

        val provasValidas = provas.filter { prova ->
            val dataInt = dataParaInt(prova.dataProva)
            if (dataInt == Int.MIN_VALUE || dataInt > hojeInt) return@filter false
            if (prova.tipoProva == "P3" && normalizar(prova.disciplina) !in disciplinasComP3) return@filter false
            true
        }

        data class TipoPrincipal(val tipo: String, val data: Int)

        val tipoPrincipalPorDisciplina: Map<String, TipoPrincipal?> = provasValidas
            .groupBy { normalizar(it.disciplina) }
            .mapValues { (_, lista) ->
                val melhorPorTipo = lista.groupBy { it.tipoProva }
                    .mapValues { (_, provasTipo) -> provasTipo.maxOf { dataParaInt(it.dataProva) } }
                val tipoEscolhido = melhorPorTipo.keys.maxByOrNull { tipoPeso(it) } ?: return@mapValues null
                TipoPrincipal(tipoEscolhido, melhorPorTipo[tipoEscolhido]!!)
            }

        val calendarioExato: Map<String, Int> = provasValidas
            .associate { "${normalizar(it.disciplina)}|${it.tipoProva}" to dataParaInt(it.dataProva) }

        val notasLancadas = notas.filter { it.valor.isNotEmpty() }
        val grupos: Map<String, List<Nota>> = notas.groupBy { normalizar(it.codigoDisciplina) }

        fun chaveOrdenacao(codigoNormalizado: String): String? {
            val tp = tipoPrincipalPorDisciplina[codigoNormalizado] ?: return null
            val principalLancada = notasLancadas.any {
                normalizar(it.codigoDisciplina) == codigoNormalizado && it.tipoProva == tp.tipo
            }
            val flag = if (principalLancada) 1 else 0
            val dataFormatada = tp.data.toString().padStart(5, '0')
            return "${tipoPeso(tp.tipo)}|$flag|$dataFormatada|$codigoNormalizado"
        }

        val disciplinasComCalendario = grupos.keys
            .filter { cod -> tipoPrincipalPorDisciplina.containsKey(cod) }
            .sortedByDescending { cod -> chaveOrdenacao(cod) ?: "" }

        val resultado = mutableListOf<Nota>()
        val avulsas = mutableListOf<Nota>()

        for (codigoNormalizado in disciplinasComCalendario) {
            val notasDaDisciplina = grupos[codigoNormalizado] ?: continue
            val tp = tipoPrincipalPorDisciplina[codigoNormalizado]!!

            notasDaDisciplina.filter { it.tipoProva == tp.tipo && it.valor.isNotEmpty() }
                .sortedBy { it.nomeDisciplina }.let { resultado.addAll(it) }

            notasDaDisciplina.filter { it.tipoProva !in tiposConhecidos && it.valor.isNotEmpty() }
                .sortedBy { it.nomeDisciplina }.let { resultado.addAll(it) }

            val outrasConhecidas = notasDaDisciplina
                .filter { it.tipoProva in tiposConhecidos && it.tipoProva != tp.tipo && it.valor.isNotEmpty() }
            avulsas.addAll(outrasConhecidas)
        }

        val setComCalendario = disciplinasComCalendario.toSet()
        for ((codigoNormalizado, lista) in grupos) {
            if (codigoNormalizado !in setComCalendario) {
                avulsas.addAll(lista.filter { it.valor.isNotEmpty() })
            }
        }

        val ancoraPorDisciplina: Map<String, Int> = provasValidas
            .groupBy { normalizar(it.disciplina) }
            .mapValues { (_, lista) -> lista.maxOf { dataParaInt(it.dataProva) } }

        fun dataReferencia(nota: Nota): Int {
            val codNorm = normalizar(nota.codigoDisciplina)
            return if (nota.tipoProva in tiposConhecidos) {
                calendarioExato["$codNorm|${nota.tipoProva}"] ?: Int.MIN_VALUE
            } else { ancoraPorDisciplina[codNorm] ?: Int.MIN_VALUE }
        }

        avulsas.sortWith(
            compareByDescending<Nota> { dataReferencia(it) }
                .thenByDescending { tipoPeso(it.tipoProva) }
                .thenBy { it.nomeDisciplina }
        )

        resultado.addAll(avulsas)
        return resultado
    }

    // ===================== FETCH =====================
    private suspend fun fetchNotasFromServer(): List<Nota> {
        val doc = SessionManager.fetchPage(URL_NOTAS)
        val container = doc.selectFirst("body > div.container > div:nth-child(2) > div.col-md-9 > div:nth-child(5)")
            ?: throw SessionExpiredException("Container das notas não encontrado")

        val panels = container.select("div.panel.panel-default")
        val notas = mutableListOf<Nota>()

        for (panel in panels) {
            val tituloLink = panel.selectFirst(".panel-title a.tabela-notas") ?: continue
            val textoCompleto = tituloLink.text().trim()
            val partes = textoCompleto.split(" - ", limit = 2)
            if (partes.size != 2) continue

            val codigo = partes[0].trim()
            val nomeDisciplina = partes[1].trim()
            val tabelaNotas = panel.selectFirst("table.table.table-striped") ?: continue
            val linhas = tabelaNotas.select("tbody > tr")

            for (linha in linhas) {
                if (linha.selectFirst("td:first-child b i") != null) continue
                val avaliacaoElement = linha.selectFirst("td.Avaliação\\:")
                val valorElement = linha.selectFirst("td.Valor\\:")
                if (avaliacaoElement != null && valorElement != null) {
                    val tipoProva = avaliacaoElement.text().trim()
                    val valor = valorElement.text().trim()
                    if (tipoProva.isNotEmpty()) {
                        notas.add(Nota(codigo, nomeDisciplina, tipoProva, valor))
                    }
                }
            }
        }
        return notas
    }

    private suspend fun fetchMediasFromServer(): Map<String, String> {
        val doc = SessionManager.fetchPage(URL_NOTAS)
        val container = doc.selectFirst("body > div.container > div:nth-child(2) > div.col-md-9 > div:nth-child(5)")
            ?: throw SessionExpiredException("Container das notas não encontrado")

        val panels = container.select("div.panel.panel-default")
        val medias = mutableMapOf<String, String>()

        for (panel in panels) {
            val tituloLink = panel.selectFirst(".panel-title a.tabela-notas") ?: continue
            val textoCompleto = tituloLink.text().trim()
            val partes = textoCompleto.split(" - ", limit = 2)
            if (partes.size != 2) continue
            val codigo = partes[0].trim()

            val tabelaNotas = panel.selectFirst("table.table") ?: continue
            val linhas = tabelaNotas.select("tbody > tr")

            for (linha in linhas) {
                val primeiraColuna = linha.selectFirst("td:first-child")?.text()?.trim() ?: ""
                if (primeiraColuna.equals("Média", ignoreCase = true)) {
                    val valorMedia = linha.select("td").getOrNull(1)?.text()?.trim() ?: ""
                    medias[codigo] = valorMedia
                    break
                }
            }
        }
        return medias
    }
}