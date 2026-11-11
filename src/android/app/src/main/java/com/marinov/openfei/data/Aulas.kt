package com.marinov.openfei.data

import android.util.Log
import com.marinov.openfei.cache.CacheHelper
import com.marinov.openfei.core.CoreResult
import com.marinov.openfei.core.OpenFeiCore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * ★ BUG 80: o diff de horário precisa saber se os NOMES foram resolvidos.
 * Sem disciplinas, `nomeDisciplina` cai para o código — comparar isso com um
 * snapshot posterior (já com nomes) gerava notificação falsa de "novo horário".
 */
internal data class AulasComNomes(
    val aulas: List<Aula>,
    val nomesResolvidos: Boolean
)

object AulasRepository {
    private const val TAG = "AulasRepository"

    suspend fun aulas(online: Boolean): List<Aula> {
        return if (online) {
            try {
                persistirAulas(fetchAulasComNomesDetalhado())
            } catch (e: SessionExpiredException) {
                throw e
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Erro ao buscar horários online", e)
                CacheHelper.getCachedAulas()
            }
        } else {
            CacheHelper.getCachedAulas()
        }
    }

    suspend fun retornaAulasDia(online: Boolean): List<Aula> {
        val todas = aulas(online)
        if (todas.isEmpty()) return emptyList()
        val diaSemana = getDiaSemanaAtual()
        return todas.filter { it.diaSemana.equals(diaSemana, ignoreCase = true) }
    }

    /**
     * Busca as aulas no Rust e resolve os nomes das disciplinas, SEM salvar no cache.
     * Compartilhado com HorariosRepository.novoHorario(), que precisa comparar
     * os dados novos com o cache antigo antes de sobrescrevê-lo.
     */
    internal suspend fun fetchAulasComNomes(): List<Aula> = fetchAulasComNomesDetalhado().aulas

    /** ★ BUG 80: variante que informa se as disciplinas estavam disponíveis. */
    internal suspend fun fetchAulasComNomesDetalhado(): AulasComNomes {
        val aulasBrutas = fetchAulasFromRust()
        val disciplinas = DisciplinasRepository.obterDisciplinas(online = true)

        // ★ BUG 81: mapa DETERMINÍSTICO.
        // `associate { codigo to nome }` fica com o ÚLTIMO duplicado. Se o core
        // devolve códigos repetidos em ordem variável, o nome resolvido muda
        // entre duas leituras e o diff acusa "novo horário" sem nada mudar.
        // Agora: agrupa, remove vazios, ordena e escolhe sempre o mesmo nome.
        val mapaNomes: Map<String, String> = disciplinas
            .filter { it.codigo.isNotBlank() && it.nome.isNotBlank() }
            .groupBy { it.codigo.trim() }
            .mapValues { (_, lista) ->
                lista.map { it.nome.trim() }.distinct().sorted().first()
            }

        val aulas = aulasBrutas.map { aula ->
            val codigo = aula.codigoDisciplina.trim()
            aula.copy(
                nomeDisciplina = mapaNomes[codigo] ?: aula.nomeDisciplina.ifBlank { codigo }
            )
        }
        return AulasComNomes(aulas = aulas, nomesResolvidos = disciplinas.isNotEmpty())
    }

    /**
     * ★ BUG 80: cache válido nunca é substituído por lista vazia nem por
     * versão sem nomes resolvidos (que envenenaria o baseline do worker).
     */
    private fun persistirAulas(resultado: AulasComNomes): List<Aula> {
        val cache = CacheHelper.getCachedAulas()
        val novas = resultado.aulas
        if (novas.isEmpty() && cache.isNotEmpty()) {
            Log.w(TAG, "Aulas vazias online — mantendo cache (${cache.size})")
            return cache
        }
        if (!resultado.nomesResolvidos && cache.isNotEmpty()) {
            Log.w(TAG, "Disciplinas indisponíveis — mantendo cache de aulas com nomes")
            return cache
        }
        CacheHelper.saveAulasCache(novas)
        return novas
    }

    private suspend fun fetchAulasFromRust(): List<Aula> = withContext(Dispatchers.IO) {
        ensureFeiSessionOrThrow("aulas")
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

    private fun getDiaSemanaAtual(): String = DiasSemana.hoje() ?: ""
}