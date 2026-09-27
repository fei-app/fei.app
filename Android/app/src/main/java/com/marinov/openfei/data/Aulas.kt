package com.marinov.openfei.data

import android.util.Log
import com.marinov.openfei.core.CoreResult
import com.marinov.openfei.core.OpenFeiCore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.Calendar

object AulasRepository {
    private const val TAG = "AulasRepository"

    suspend fun aulas(online: Boolean): List<Aula> {
        return if (online) {
            try {
                val aulasBrutas = fetchAulasFromRust()
                val disciplinas = DisciplinasRepository.obterDisciplinas(online = true)
                val mapaNomes = disciplinas.associate { it.codigo to it.nome }
                val aulasComNomes = aulasBrutas.map { aula ->
                    aula.copy(nomeDisciplina = mapaNomes[aula.codigoDisciplina] ?: aula.codigoDisciplina)
                }
                CacheHelper.saveAulasCache(aulasComNomes)
                aulasComNomes
            } catch (e: SessionExpiredException) {
                throw e
            } catch (e: Exception) {
                if (e !is CancellationException) {
                    Log.e(TAG, "Erro ao buscar horários online", e)
                }
                CacheHelper.getCachedAulas()
            }
        } else {
            CacheHelper.getCachedAulas()
        }
    }

    suspend fun novoHorario(online: Boolean): Boolean {
        if (!online) return false
        try {
            val novasAulas = fetchAulasFromRust()
            val disciplinas = DisciplinasRepository.obterDisciplinas(online = true)
            val mapaNomes = disciplinas.associate { it.codigo to it.nome }
            val novasComNomes = novasAulas.map { it.copy(nomeDisciplina = mapaNomes[it.codigoDisciplina] ?: it.codigoDisciplina) }
            val antigasAulas = CacheHelper.getCachedAulas()
            if (antigasAulas.isEmpty()) {
                CacheHelper.saveAulasCache(novasComNomes)
                return false
            }
            val alterado = novasComNomes.toSet() != antigasAulas.toSet()
            if (alterado) CacheHelper.saveAulasCache(novasComNomes)
            return alterado
        } catch (e: SessionExpiredException) {
            throw e
        } catch (e: Exception) {
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

    private suspend fun fetchAulasFromRust(): List<Aula> = withContext(Dispatchers.IO) {
        // ★ CORREÇÃO: Garante sessão FEI antes de buscar aulas
        if (!RustSession.ensureFeiSession()) {
            throw SessionExpiredException("Não foi possível garantir sessão FEI para aulas")
        }

        when (val result = OpenFeiCore.fetchAulas()) {
            is CoreResult.Success -> {
                result.data.map { core ->
                    Aula(
                        diaSemana = core.diaSemana,
                        codigoDisciplina = core.codigoDisciplina,
                        nomeDisciplina = core.nomeDisciplina,
                        sala = core.sala,
                        horaInicio = core.horaInicio,
                        horaFim = core.horaFim
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

    private fun getDiaSemanaAtual(): String {
        val calendar = Calendar.getInstance()
        return when (calendar.get(Calendar.DAY_OF_WEEK)) {
            Calendar.MONDAY -> "Segunda"
            Calendar.TUESDAY -> "Terça"
            Calendar.WEDNESDAY -> "Quarta"
            Calendar.THURSDAY -> "Quinta"
            Calendar.FRIDAY -> "Sexta"
            Calendar.SATURDAY -> "Sábado"
            else -> ""
        }
    }
}