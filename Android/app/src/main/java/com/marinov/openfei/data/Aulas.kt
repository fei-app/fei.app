package com.marinov.openfei.data

import android.util.Log
import kotlinx.coroutines.CancellationException
import java.util.Calendar

object AulasRepository {
    private const val TAG = "AulasRepository"
    private const val URL_HORARIO = "https://interage.fei.org.br/secureserver/portal/graduacao/secretaria/consultas/horario/arquivo"

    suspend fun aulas(online: Boolean): List<Aula> {
        return if (online) {
            try {
                val aulasBrutas = fetchAulasFromServer()
                val disciplinas = DisciplinasRepository.obterDisciplinas(online = true)
                val mapaNomes = disciplinas.associate { it.codigo to it.nome }
                val aulasComNomes = aulasBrutas.map { aula ->
                    aula.copy(nomeDisciplina = mapaNomes[aula.codigoDisciplina] ?: aula.codigoDisciplina)
                }
                CacheHelper.saveAulasCache(aulasComNomes)
                aulasComNomes
            } catch (e: SessionExpiredException) { throw e }
            catch (e: Exception) {
                if (e !is CancellationException) Log.e(TAG, "Erro ao buscar horários online", e)
                CacheHelper.getCachedAulas()
            }
        } else { CacheHelper.getCachedAulas() }
    }

    suspend fun novoHorario(online: Boolean): Boolean {
        if (!online) return false
        try {
            val novasAulas = fetchAulasFromServer()
            val disciplinas = DisciplinasRepository.obterDisciplinas(online = true)
            val mapaNomes = disciplinas.associate { it.codigo to it.nome }
            val novasComNomes = novasAulas.map { it.copy(nomeDisciplina = mapaNomes[it.codigoDisciplina] ?: it.codigoDisciplina) }
            val antigasAulas = CacheHelper.getCachedAulas()

            if (antigasAulas.isEmpty()) { CacheHelper.saveAulasCache(novasComNomes); return false }

            val alterado = novasComNomes.toSet() != antigasAulas.toSet()
            if (alterado) CacheHelper.saveAulasCache(novasComNomes)
            return alterado
        } catch (e: SessionExpiredException) { throw e }
        catch (e: Exception) {
            Log.e(TAG, "Erro em novoHorario", e)
            return false
        }
    }

    suspend fun retornaAulasDia(online: Boolean): List<Aula> {
        val todas = aulas(online)
        if (todas.isEmpty()) return emptyList()
        val diaSemana = getDiaSemanaAtual()
        return todas.filter { it.diaSemana.equals(diaSemana, ignoreCase = true) }
    }

    // ===================== FETCH =====================
    private suspend fun fetchAulasFromServer(): List<Aula> {
        val doc = SessionManager.fetchPage(URL_HORARIO)
        val tabela = doc.selectFirst("#tb_princ")
            ?: throw SessionExpiredException("Tabela de horários não encontrada - sessão inválida")

        val linhas = tabela.select("tr")
        if (linhas.size < 3) return emptyList()

        val colunasPorDia = mapOf(
            "Segunda" to (1 to 3), "Terça" to (4 to 6), "Quarta" to (7 to 9),
            "Quinta" to (10 to 12), "Sexta" to (13 to 15), "Sábado" to (17 to 19)
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

                val cellDisc = cells[colDisc]; val cellSala = cells[colSala]
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
                } else { aulasAgrupadas.add(current); current = next }
            }
            aulasAgrupadas.add(current)
        }
        return aulasAgrupadas
    }

    private fun extrairHorario(texto: String): Pair<String, String>? {
        val regex = Regex("(\\d{2}:\\d{2})\\s*-\\s*(\\d{2}:\\d{2})")
        val match = regex.find(texto) ?: return null
        return Pair(match.groupValues[1], match.groupValues[2])
    }

    private fun getDiaSemanaAtual(): String {
        val calendar = Calendar.getInstance()
        return when (calendar.get(Calendar.DAY_OF_WEEK)) {
            Calendar.MONDAY -> "Segunda"; Calendar.TUESDAY -> "Terça"
            Calendar.WEDNESDAY -> "Quarta"; Calendar.THURSDAY -> "Quinta"
            Calendar.FRIDAY -> "Sexta"; Calendar.SATURDAY -> "Sábado"
            else -> ""
        }
    }
}