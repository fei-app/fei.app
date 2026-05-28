package com.marinov.openfei

import android.content.Context
import android.content.SharedPreferences
import android.webkit.CookieManager
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Calendar
import androidx.core.content.edit
import androidx.core.content.FileProvider
import java.io.File

class SessionExpiredException(message: String) : Exception(message)

object Dados {

    private const val PREFS_NAME = "DadosFEI"
    private const val KEY_DISCIPLINAS = "disciplinas_cache"
    private const val KEY_PERFIL = "perfil_cache"
    private const val KEY_AULAS = "aulas_cache"
    private const val KEY_CALENDARIO_PROVAS = "calendario_provas_cache"
    private const val KEY_LAST_UPDATE_DISCIPLINAS = "last_update_disciplinas"
    private const val KEY_LAST_UPDATE_NOTAS = "last_update_notas"
    private const val KEY_LAST_UPDATE_PERFIL = "last_update_perfil"
    private const val KEY_LAST_UPDATE_AULAS = "last_update_aulas"
    private const val KEY_LAST_UPDATE_CALENDARIO_PROVAS = "last_update_calendario_provas"
    private const val KEY_BOLETOS = "boletos_cache"
    private const val KEY_LAST_UPDATE_BOLETOS = "last_update_boletos"
    private const val URL_DISCIPLINAS = "https://interage.fei.org.br/secureserver/portal/graduacao/sala-dos-professores/consultas/tabela-de-aulas"
    private const val URL_NOTAS = "https://interage.fei.org.br/secureserver/portal/graduacao/secretaria/consultas/notas"
    private const val URL_PERFIL = "https://interage.fei.org.br/secureserver/portal/graduacao/secretaria/dados-pessoais"
    private const val URL_HORARIO = "https://interage.fei.org.br/secureserver/portal/graduacao/secretaria/consultas/horario/arquivo"
    private const val URL_CALENDARIO_PROVAS = "https://interage.fei.org.br/secureserver/portal/graduacao/sala-dos-professores/informacoes-academicas/provas"
    private const val URL_BOLETOS = "https://interage.fei.org.br/secureserver/portal/graduacao/tesouraria/consultas/boletos"
    private const val URL_GERAR_BOLETO = "https://interage.fei.org.br/secureserver/portal/graduacao/tesouraria/consultas/boletos/titulos/gerar"
    private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 16; sdk_gphone64_x86_64 Build/BE2A.250530.026.D1; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/133.0.6943.137 Mobile Safari/537.36"
    private lateinit var appContext: Context
    private val gson = Gson()
    private val prefs: SharedPreferences by lazy { appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    // ===================== MODELOS DE DADOS =====================
    data class Disciplina(val codigo: String, val nome: String)

    data class Nota(
        val codigoDisciplina: String,
        val nomeDisciplina: String,
        val tipoProva: String,
        val valor: String
    )

    data class Perfil(
        val nome: String,
        val matricula: String,
        val curso: String,
        val email: String
    )

    data class Aula(
        val diaSemana: String,
        val codigoDisciplina: String,
        val nomeDisciplina: String,
        val sala: String,
        val horaInicio: String,
        val horaFim: String
    )

    data class ProvaCalendario(
        val disciplina: String,
        val nomeDisciplina: String,
        val dataProva: String,
        val hora: String,
        val sala: String,
        val coordenador: String,
        val tipoProva: String
    )

    data class Boleto(
        val vencimento: String,
        val status: String,
        val dataPagamento: String,
        val tituloId: String
    )

    // ===================== FUNÇÕES PÚBLICAS =====================
    suspend fun obterDisciplinas(online: Boolean): List<Disciplina> {
        return if (online) {
            try {
                val disciplinas = fetchDisciplinasFromServer()
                saveDisciplinasCache(disciplinas)
                disciplinas
            } catch (e: SessionExpiredException) {
                throw e
            } catch (e: Exception) {
                if (e !is CancellationException) Log.e("Dados", "Erro ao buscar disciplinas online", e)
                getCachedDisciplinas()
            }
        } else {
            getCachedDisciplinas()
        }
    }

    suspend fun obterNotas(online: Boolean): List<Nota> {
        return if (online) {
            try {
                val notas = fetchNotasFromServer()
                saveNotasCache(notas)
                notas
            } catch (e: SessionExpiredException) {
                throw e
            } catch (e: Exception) {
                if (e !is CancellationException) Log.e("Dados", "Erro ao buscar notas online", e)
                getCachedNotas()
            }
        } else {
            getCachedNotas()
        }
    }

    suspend fun atualizarNotas(online: Boolean): List<Nota> {
        if (!online) return emptyList()
        try {
            val novasNotas = fetchNotasFromServer()
            val antigasNotas = getCachedNotas()

            if (antigasNotas.isEmpty()) {
                saveNotasCache(novasNotas)
                return emptyList()
            }

            val mapaAntigas = antigasNotas.associateBy { "${it.codigoDisciplina}|${it.tipoProva}" }
            val notasAlteradas = novasNotas.filter { nova ->
                val chave = "${nova.codigoDisciplina}|${nova.tipoProva}"
                mapaAntigas[chave]?.valor != nova.valor
            }
            saveNotasCache(novasNotas)
            return notasAlteradas
        } catch (e: SessionExpiredException) {
            throw e
        } catch (e: Exception) {
            Log.e("Dados", "Erro em atualizarNotas", e)
            return emptyList()
        }
    }

    suspend fun retornaDadosUsuario(online: Boolean): Perfil {
        return if (online) {
            try {
                val perfil = fetchPerfilFromServer()
                savePerfilCache(perfil)
                perfil
            } catch (e: SessionExpiredException) {
                throw e
            } catch (e: Exception) {
                if (e !is CancellationException) Log.e("Dados", "Erro ao buscar perfil online", e)
                getCachedPerfil() ?: Perfil("", "", "", "")
            }
        } else {
            getCachedPerfil() ?: Perfil("", "", "", "")
        }
    }

    suspend fun aulas(online: Boolean): List<Aula> {
        return if (online) {
            try {
                val aulasBrutas = fetchAulasFromServer()
                val disciplinas = obterDisciplinas(online = true)
                val mapaNomes = disciplinas.associate { it.codigo to it.nome }
                val aulasComNomes = aulasBrutas.map { aula ->
                    aula.copy(nomeDisciplina = mapaNomes[aula.codigoDisciplina] ?: aula.codigoDisciplina)
                }
                saveAulasCache(aulasComNomes)
                aulasComNomes
            } catch (e: SessionExpiredException) {
                throw e
            } catch (e: Exception) {
                if (e !is CancellationException) Log.e("Dados", "Erro ao buscar horários online", e)
                getCachedAulas()
            }
        } else {
            getCachedAulas()
        }
    }

    suspend fun novoHorario(online: Boolean): Boolean {
        if (!online) return false
        try {
            val novasAulas = fetchAulasFromServer()
            val disciplinas = obterDisciplinas(online = true)
            val mapaNomes = disciplinas.associate { it.codigo to it.nome }
            val novasComNomes = novasAulas.map { it.copy(nomeDisciplina = mapaNomes[it.codigoDisciplina] ?: it.codigoDisciplina) }
            val antigasAulas = getCachedAulas()

            if (antigasAulas.isEmpty()) {
                saveAulasCache(novasComNomes)
                return false
            }

            val alterado = novasComNomes.toSet() != antigasAulas.toSet()
            if (alterado) {
                saveAulasCache(novasComNomes)
            }
            return alterado
        } catch (e: SessionExpiredException) {
            throw e
        } catch (e: Exception) {
            Log.e("Dados", "Erro em novoHorario", e)
            return false
        }
    }

    suspend fun retornaAulasDia(online: Boolean): List<Aula> {
        val todas = aulas(online)
        if (todas.isEmpty()) return emptyList()
        val diaSemana = getDiaSemanaAtual()
        return todas.filter { it.diaSemana.equals(diaSemana, ignoreCase = true) }
    }

    suspend fun obterCalendarioProvas(online: Boolean): List<ProvaCalendario> {
        return if (online) {
            try {
                val provas = fetchCalendarioProvasFromServer()
                saveProvasCalendarioCache(provas)
                provas
            } catch (e: SessionExpiredException) {
                throw e
            } catch (e: Exception) {
                if (e !is CancellationException) Log.e("Dados", "Erro ao buscar calendário de provas online", e)
                getCachedProvasCalendario()
            }
        } else {
            getCachedProvasCalendario()
        }
    }

    // ===================== BOLETOS =====================
    suspend fun getBoletos(online: Boolean): List<Boleto> {
        return if (online) {
            try {
                val boletos = fetchBoletosFromServer()
                saveBoletosCache(boletos)
                boletos
            } catch (e: SessionExpiredException) {
                throw e
            } catch (e: Exception) {
                if (e !is CancellationException) Log.e("Dados", "Erro ao buscar boletos online", e)
                getCachedBoletos()
            }
        } else {
            getCachedBoletos()
        }
    }

    suspend fun atualizaBoletos(): Boolean {
        return try {
            val novos = fetchBoletosFromServer()
            val antigos = getCachedBoletos()

            if (antigos.isEmpty()) {
                saveBoletosCache(novos)
                return false
            }

            val alterado = novos.size != antigos.size ||
                    novos.zip(antigos).any { (novo, antigo) ->
                        novo.vencimento != antigo.vencimento ||
                                novo.status != antigo.status ||
                                novo.dataPagamento != antigo.dataPagamento
                    }
            if (alterado) saveBoletosCache(novos)
            alterado
        } catch (e: SessionExpiredException) {
            throw e
        } catch (e: Exception) {
            Log.e("Dados", "Erro em atualizaBoletos", e)
            false
        }
    }

    suspend fun baixaBoleto(tituloId: String, vencimento: String): android.net.Uri? = withContext(Dispatchers.IO) {
        try {
            val partes = vencimento.split("/")
            val nomeArquivo = if (partes.size == 3) {
                "${partes[2]}_${partes[1]}.pdf"
            } else {
                "$tituloId.pdf"
            }

            val pageDoc = fetchPage(URL_BOLETOS)
            val csrfToken = pageDoc
                .selectFirst("#form-gerar-boletos input[name=__RequestVerificationToken]")
                ?.`val`()
                ?: run {
                    Log.e("Dados", "CSRF token não encontrado na página de boletos")
                    return@withContext null
                }
            val cookieStr = CookieManager.getInstance().getCookie(URL_BOLETOS) ?: ""
            val postData = buildString {
                append("__RequestVerificationToken=")
                append(URLEncoder.encode(csrfToken, "UTF-8"))
                append("&respFinanceiro=0")
                append("&titulos=")
                append(URLEncoder.encode(tituloId, "UTF-8"))
            }.toByteArray(Charsets.UTF_8)

            var conn = URL(URL_GERAR_BOLETO).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.doInput = true
            conn.connectTimeout = 30_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            conn.setRequestProperty("Content-Length", postData.size.toString())
            conn.setRequestProperty("Cookie", cookieStr)
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.setRequestProperty("Referer", URL_BOLETOS)
            conn.setRequestProperty("Accept", "application/pdf,text/html,*/*")
            conn.outputStream.use { it.write(postData) }
            var responseCode = conn.responseCode
            var redirectCount = 0
            while (responseCode in 301..302 && redirectCount < 5) {
                val location = conn.getHeaderField("Location") ?: break
                conn.disconnect()
                val nextUrl = if (location.startsWith("http")) location
                else "https://interage.fei.org.br$location"
                val redirectConn = URL(nextUrl).openConnection() as HttpURLConnection
                redirectConn.instanceFollowRedirects = false
                redirectConn.requestMethod = "GET"
                redirectConn.connectTimeout = 30_000
                redirectConn.readTimeout = 30_000
                redirectConn.setRequestProperty("Cookie", cookieStr)
                redirectConn.setRequestProperty("User-Agent", USER_AGENT)
                responseCode = redirectConn.responseCode
                conn = redirectConn
                redirectCount++
            }

            Log.d("Dados", "BaixaBoleto: HTTP $responseCode, Content-Type=${conn.contentType}")

            val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOWNLOADS
            )
            val boletoDir = File(downloadsDir, "BoletosFEI").also { it.mkdirs() }
            val outputFile = File(boletoDir, nomeArquivo)

            conn.inputStream.use { input ->
                outputFile.outputStream().use { output -> input.copyTo(output) }
            }
            conn.disconnect()

            Log.d("Dados", "Boleto salvo: ${outputFile.absolutePath} (${outputFile.length()} bytes)")

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
        } catch (e: Exception) {
            Log.e("Dados", "Erro ao baixar boleto $tituloId", e)
            null
        }
    }

    fun obterCalendarioProvasCache(): List<ProvaCalendario> = getCachedProvasCalendario()

    fun ordenarNotasParaHome(
        notas: List<Nota>,
        provas: List<ProvaCalendario>
    ): List<Nota> {
        val tiposConhecidos = setOf("P1", "P2", "P3")

        fun normalizar(cod: String) = cod.trim().uppercase()

        fun dataParaInt(data: String): Int {
            val partes = data.split("/")
            if (partes.size < 2) return Int.MIN_VALUE
            val dia = partes[0].toIntOrNull() ?: return Int.MIN_VALUE
            val mes = partes[1].toIntOrNull() ?: return Int.MIN_VALUE
            return mes * 100 + dia
        }

        fun tipoPeso(tipo: String): Int = when (tipo) {
            "P3" -> 3
            "P2" -> 2
            "P1" -> 1
            else -> 0
        }

        val hoje = Calendar.getInstance()
        val hojeInt = (hoje.get(Calendar.MONTH) + 1) * 100 + hoje.get(Calendar.DAY_OF_MONTH)

        val disciplinasComP3 = notas
            .filter { it.tipoProva == "P3" && it.valor.isNotEmpty() }
            .map { normalizar(it.codigoDisciplina) }
            .toSet()

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
                val melhorPorTipo = lista
                    .groupBy { it.tipoProva }
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

        // Disciplinas com tipo principal, ordenadas pela chave (decrescente)
        val disciplinasComCalendario = grupos.keys
            .filter { cod -> tipoPrincipalPorDisciplina.containsKey(cod) }
            .sortedByDescending { cod: String -> chaveOrdenacao(cod) ?: "" }

        val resultado = mutableListOf<Nota>()
        val avulsas = mutableListOf<Nota>()

        for (codigoNormalizado in disciplinasComCalendario) {
            val notasDaDisciplina = grupos[codigoNormalizado] ?: continue
            val tp = tipoPrincipalPorDisciplina[codigoNormalizado]!!

            // 1. Nota do tipo principal (se lançada)
            notasDaDisciplina
                .filter { it.tipoProva == tp.tipo && it.valor.isNotEmpty() }
                .sortedBy { it.nomeDisciplina }
                .let { resultado.addAll(it) }

            // 2. Notas desconhecidas da disciplina
            notasDaDisciplina
                .filter { it.tipoProva !in tiposConhecidos && it.valor.isNotEmpty() }
                .sortedBy { it.nomeDisciplina }
                .let { resultado.addAll(it) }

            // 3. Outras conhecidas → avulsas
            val outrasConhecidas = notasDaDisciplina
                .filter { it.tipoProva in tiposConhecidos && it.tipoProva != tp.tipo && it.valor.isNotEmpty() }
            avulsas.addAll(outrasConhecidas)
        }

        // Disciplinas sem tipo principal → todas as notas viram avulsas
        val setComCalendario = disciplinasComCalendario.toSet()
        for ((codigoNormalizado, lista) in grupos) {
            if (codigoNormalizado !in setComCalendario) {
                avulsas.addAll(lista.filter { it.valor.isNotEmpty() })
            }
        }

        // Ordenação das avulsas: data, tipo, nome
        val ancoraPorDisciplina: Map<String, Int> = provasValidas
            .groupBy { normalizar(it.disciplina) }
            .mapValues { (_, lista) -> lista.maxOf { dataParaInt(it.dataProva) } }

        fun dataReferencia(nota: Nota): Int {
            val codNorm = normalizar(nota.codigoDisciplina)
            return if (nota.tipoProva in tiposConhecidos) {
                calendarioExato["$codNorm|${nota.tipoProva}"] ?: Int.MIN_VALUE
            } else {
                ancoraPorDisciplina[codNorm] ?: Int.MIN_VALUE
            }
        }

        avulsas.sortWith(
            compareByDescending<Nota> { dataReferencia(it) }
                .thenByDescending { tipoPeso(it.tipoProva) }
                .thenBy { it.nomeDisciplina }
        )

        resultado.addAll(avulsas)
        return resultado
    }
    // ===================== FUNÇÕES PRIVADAS DE REDE =====================
    @Throws(IOException::class)
    private suspend fun fetchPage(url: String): Document = withContext(Dispatchers.IO) {
        val cookies = CookieManager.getInstance().getCookie(url)
        try {
            val conn = Jsoup.connect(url)
                .userAgent(USER_AGENT)
                .timeout(20000)
            if (!cookies.isNullOrBlank()) {
                conn.header("Cookie", cookies)
            }
            conn.get()
        } catch (e: IOException) {
            Log.e("Dados", "Erro de rede ao buscar $url", e)
            throw e
        }
    }

    private suspend fun fetchDisciplinasFromServer(): List<Disciplina> {
        val doc = fetchPage(URL_DISCIPLINAS)
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
        Log.d("Dados", "Disciplinas carregadas: ${disciplinas.size}")
        return disciplinas
    }

    private suspend fun fetchNotasFromServer(): List<Nota> {
        val doc = fetchPage(URL_NOTAS)
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
        Log.d("Dados", "Notas carregadas: ${notas.size}")
        return notas
    }

    private suspend fun fetchPerfilFromServer(): Perfil {
        val doc = fetchPage(URL_PERFIL)
        val panelBody = doc.selectFirst("body > div.container > div:nth-child(2) > div.col-md-9 > div.panel.panel-default.hidden-xs.bloco-conteudo-cabecalho > div.panel-body")
            ?: throw SessionExpiredException("Painel de perfil não encontrado")
        var nome = ""
        var matricula = ""
        var curso = ""
        panelBody.children().forEach { col ->
            val b = col.selectFirst("b")?.text()?.trim() ?: ""
            val em = col.selectFirst("small em")?.text()?.trim() ?: ""
            when {
                b.equals("Nome", ignoreCase = true) -> nome = em
                b.equals("Matrícula", ignoreCase = true) -> matricula = em
                b.equals("Curso", ignoreCase = true) -> curso = em
            }
        }
        val emailGroup = doc.selectFirst("#form-atualizar-dados-pessoais > div:nth-child(19)")
        val emailElement = emailGroup?.selectFirst("p.form-control-static")
        val email = emailElement?.text()?.trim() ?: ""
        Log.d("Dados", "Perfil carregado: $nome")
        return Perfil(nome, matricula, curso, email)
    }

    private suspend fun fetchAulasFromServer(): List<Aula> {
        val doc = fetchPage(URL_HORARIO)
        val tabela = doc.selectFirst("#tb_princ")
            ?: throw SessionExpiredException("Tabela de horários não encontrada - sessão inválida")
        val linhas = tabela.select("tr")
        if (linhas.size < 3) return emptyList()

        val colunasPorDia = mapOf(
            "Segunda" to (1 to 3),
            "Terça"   to (4 to 6),
            "Quarta"  to (7 to 9),
            "Quinta"  to (10 to 12),
            "Sexta"   to (13 to 15),
            "Sábado"  to (17 to 19)
        )

        val aulasPorDia = mutableMapOf<String, MutableList<Aula>>()
        colunasPorDia.keys.forEach { dia -> aulasPorDia[dia] = mutableListOf() }
        for (rowIdx in 2 until linhas.size) {
            val row = linhas[rowIdx]
            val cells = row.select("td")
            if (cells.size < 20) continue
            val horaTexto = cells[0].text().trim()
            val horarioPadrao = extrairHorario(horaTexto)

            var horarioSabado: Pair<String, String>? = null
            for (cell in cells) {
                if (cell.classNames().any { it.contains("sabado", ignoreCase = true) }) {
                    val txt = cell.text().trim()
                    extrairHorario(txt)?.let { horarioSabado = it }
                    break
                }
            }

            for ((dia, colunas) in colunasPorDia) {
                val (colDisc, colSala) = colunas
                if (colDisc >= cells.size || colSala >= cells.size) continue
                val cellDisc = cells[colDisc]
                val cellSala = cells[colSala]

                var codigo = cellDisc.text().trim()
                val sala = cellSala.text().trim()

                if (codigo.isEmpty() || codigo == " ") continue
                codigo = codigo.replace(Regex("\\s+"), " ").trim()

                val horario = if (dia == "Sábado") horarioSabado else horarioPadrao
                if (horario != null) {
                    aulasPorDia[dia]?.add(Aula(dia, codigo, "", sala, horario.first, horario.second))
                }
            }
        }

        val aulasAgrupadas = mutableListOf<Aula>()
        for ((_, lista) in aulasPorDia) {
            if (lista.isEmpty()) continue
            val sorted = lista.sortedBy { it.horaInicio }
            var current = sorted[0]
            for (i in 1 until sorted.size) {
                val next = sorted[i]
                if (current.codigoDisciplina == next.codigoDisciplina) {
                    current = current.copy(horaFim = next.horaFim, sala = next.sala)
                } else {
                    aulasAgrupadas.add(current)
                    current = next
                }
            }
            aulasAgrupadas.add(current)
        }

        Log.d("Dados", "Aulas brutas (por dia): ${aulasPorDia.values.flatten().size}")
        Log.d("Dados", "Aulas agrupadas: ${aulasAgrupadas.size}")
        return aulasAgrupadas
    }

    private suspend fun fetchCalendarioProvasFromServer(): List<ProvaCalendario> {
        val disciplinas = obterDisciplinas(online = true)
        val mapaNomes = disciplinas.associate { it.codigo to it.nome }
        val doc = fetchPage(URL_CALENDARIO_PROVAS)
        val accordion = doc.selectFirst("#accordion-provas")
            ?: throw SessionExpiredException("Accordion de provas não encontrado")

        val provas = mutableListOf<ProvaCalendario>()
        val panels = accordion.select("div.panel.panel-default")

        for (panel in panels) {
            val tituloLink = panel.selectFirst(".panel-title a")
            val titulo = tituloLink?.text()?.trim() ?: continue
            val tipoProva = when {
                titulo.contains("(P1)") -> "P1"
                titulo.contains("(P2)") -> "P2"
                titulo.contains("(P3)") -> "P3"
                else -> continue
            }

            val tabela = panel.selectFirst("div.panel-body table.table")
            if (tabela == null) {
                Log.d("Dados", "Tabela não encontrada para $tipoProva (provavelmente sem registros)")
                continue
            }

            val linhas = tabela.select("tbody > tr")
            for (linha in linhas) {
                val disciplinaElem = linha.selectFirst("td[class*=\"Disciplina\"]")
                val provaElem = linha.selectFirst("td[class*=\"Prova\"]")
                val horaElem = linha.selectFirst("td[class*=\"Hora\"]")
                val salaElem = linha.selectFirst("td[class*=\"Sala\"]")
                val coordenadorElem = linha.selectFirst("td[class*=\"Coordenador\"]")

                if (disciplinaElem == null || provaElem == null || horaElem == null ||
                    salaElem == null || coordenadorElem == null) {
                    continue
                }

                val codigo = disciplinaElem.text().trim()
                val provaTexto = provaElem.text().trim()
                val hora = horaElem.text().trim()
                val sala = salaElem.text().trim()
                val coordenador = coordenadorElem.text().trim()

                val dataProva = provaTexto.split(" ").firstOrNull() ?: provaTexto

                if (codigo.isNotEmpty() && dataProva.isNotEmpty()) {
                    val nome = mapaNomes[codigo] ?: codigo
                    provas.add(
                        ProvaCalendario(
                            disciplina = codigo,
                            nomeDisciplina = nome,
                            dataProva = dataProva,
                            hora = hora,
                            sala = sala,
                            coordenador = coordenador,
                            tipoProva = tipoProva
                        )
                    )
                }
            }
        }

        Log.d("Dados", "Calendário de provas carregado: ${provas.size} itens")
        return provas
    }

    private suspend fun fetchBoletosFromServer(): List<Boleto> {
        val doc = fetchPage(URL_BOLETOS)
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

        Log.d("Dados", "Boletos carregados: ${boletos.size}")
        return boletos
    }

    private fun extrairHorario(texto: String): Pair<String, String>? {
        val regex = Regex("(\\d{2}:\\d{2})\\s*-\\s*(\\d{2}:\\d{2})")
        val match = regex.find(texto) ?: return null
        return Pair(match.groupValues[1], match.groupValues[2])
    }

    private fun getDiaSemanaAtual(): String {
        val calendar = Calendar.getInstance()
        val dia = calendar.get(Calendar.DAY_OF_WEEK)
        return when (dia) {
            Calendar.MONDAY    -> "Segunda"
            Calendar.TUESDAY   -> "Terça"
            Calendar.WEDNESDAY -> "Quarta"
            Calendar.THURSDAY  -> "Quinta"
            Calendar.FRIDAY    -> "Sexta"
            Calendar.SATURDAY  -> "Sábado"
            else -> ""
        }
    }

    // ===================== CACHE =====================
    fun clearAllCacheFiles() {
        listOf(
            "notas_cache.json",
            "disciplinas_cache.json",
            "aulas_cache.json",
            "provas_cache.json",
            "boletos_cache.json",
            "perfil_cache.json"
        ).forEach { nome ->
            File(appContext.filesDir, nome).delete()
        }
        prefs.edit {
            remove(KEY_LAST_UPDATE_NOTAS)
            remove(KEY_LAST_UPDATE_DISCIPLINAS)
            remove(KEY_LAST_UPDATE_AULAS)
            remove(KEY_LAST_UPDATE_CALENDARIO_PROVAS)
            remove(KEY_LAST_UPDATE_BOLETOS)
            remove(KEY_LAST_UPDATE_PERFIL)
        }
    }

    private fun saveDisciplinasCache(disciplinas: List<Disciplina>) {
        prefs.edit {
            putString(KEY_DISCIPLINAS, gson.toJson(disciplinas))
                .putLong(KEY_LAST_UPDATE_DISCIPLINAS, System.currentTimeMillis())
        }
    }

    private fun getCachedDisciplinas(): List<Disciplina> {
        val json = prefs.getString(KEY_DISCIPLINAS, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<Disciplina>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (_: Exception) { emptyList() }
    }

    private fun saveNotasCache(notas: List<Nota>) {
        val file = File(appContext.filesDir, "notas_cache.json")
        file.writeText(gson.toJson(notas))
        prefs.edit { putLong(KEY_LAST_UPDATE_NOTAS, System.currentTimeMillis()) }
    }

    private fun getCachedNotas(): List<Nota> {
        val file = File(appContext.filesDir, "notas_cache.json")
        if (!file.exists()) return emptyList()
        return try {
            val type = object : TypeToken<List<Nota>>() {}.type
            gson.fromJson(file.readText(), type) ?: emptyList()
        } catch (_: Exception) { emptyList() }
    }

    private fun savePerfilCache(perfil: Perfil) {
        prefs.edit {
            putString(KEY_PERFIL, gson.toJson(perfil))
                .putLong(KEY_LAST_UPDATE_PERFIL, System.currentTimeMillis())
        }
    }

    private fun getCachedPerfil(): Perfil? {
        val json = prefs.getString(KEY_PERFIL, null) ?: return null
        return try {
            gson.fromJson(json, Perfil::class.java)
        } catch (_: Exception) { null }
    }

    private fun saveAulasCache(aulas: List<Aula>) {
        prefs.edit {
            putString(KEY_AULAS, gson.toJson(aulas))
                .putLong(KEY_LAST_UPDATE_AULAS, System.currentTimeMillis())
        }
    }

    private fun getCachedAulas(): List<Aula> {
        val json = prefs.getString(KEY_AULAS, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<Aula>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (_: Exception) { emptyList() }
    }

    private fun saveProvasCalendarioCache(provas: List<ProvaCalendario>) {
        prefs.edit {
            putString(KEY_CALENDARIO_PROVAS, gson.toJson(provas))
                .putLong(KEY_LAST_UPDATE_CALENDARIO_PROVAS, System.currentTimeMillis())
        }
    }

    private fun getCachedProvasCalendario(): List<ProvaCalendario> {
        val json = prefs.getString(KEY_CALENDARIO_PROVAS, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<ProvaCalendario>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (_: Exception) { emptyList() }
    }

    private fun saveBoletosCache(boletos: List<Boleto>) {
        prefs.edit {
            putString(KEY_BOLETOS, gson.toJson(boletos))
                .putLong(KEY_LAST_UPDATE_BOLETOS, System.currentTimeMillis())
        }
    }

    private fun getCachedBoletos(): List<Boleto> {
        val json = prefs.getString(KEY_BOLETOS, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<Boleto>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (_: Exception) { emptyList() }
    }
}