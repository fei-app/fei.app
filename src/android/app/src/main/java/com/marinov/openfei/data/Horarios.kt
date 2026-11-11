package com.marinov.openfei.data

import android.content.Context
import android.util.Log
import com.marinov.openfei.cache.CacheHelper
import com.marinov.openfei.cache.SyncBaseline
import kotlin.coroutines.cancellation.CancellationException

object HorariosRepository {
    private const val TAG = "HorariosRepository"

    /** ★ BUG 80: contexto para o marco de primeira execução. */
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /**
     * ★ BUG 30: compara preservando duplicatas (troca de sala em aula repetida).
     * ★ BUG 80: só compara quando existe baseline confirmado pelo sync.
     * ★ BUG 81: a comparação agora é INSENSÍVEL À ORDEM e normalizada.
     *
     * O `zip(antigas)` anterior comparava por POSIÇÃO: bastava o core Rust
     * devolver as mesmas aulas em outra ordem para o app notificar "novo
     * horário" a cada sincronização, sem nada ter mudado no portal.
     *
     * Nunca notifica quando:
     *  - não há contexto / primeira execução / cache vazio;
     *  - o servidor devolveu 0 aulas;
     *  - os nomes das disciplinas não puderam ser resolvidos;
     *  - duas leituras consecutivas divergem (fonte instável);
     *  - a diferença é apenas ordem, espaços, caixa ou formato de hora.
     */
    suspend fun novoHorario(online: Boolean): Boolean {
        if (!online) return false
        return try {
            val ctx = appContext
            if (ctx == null) {
                Log.w(TAG, "HorariosRepository sem contexto — sync sem notificação")
                return false
            }

            val primeiraLeitura = AulasRepository.fetchAulasComNomesDetalhado()
            val novas = primeiraLeitura.aulas

            // 1) Dados nulos do servidor.
            if (novas.isEmpty()) {
                Log.w(TAG, "Horário: servidor devolveu 0 aulas — nada a comparar")
                return false
            }

            // 2) Nomes não resolvidos: comparar agora geraria falso positivo.
            if (!primeiraLeitura.nomesResolvidos) {
                Log.w(TAG, "Horário: disciplinas indisponíveis — diff adiado (sem notificação)")
                return false
            }

            val antigas = CacheHelper.getCachedAulas()

            // 3) Primeira execução (ou cache perdido): semeia em silêncio.
            if (!SyncBaseline.isEstablished(ctx, SyncBaseline.AULAS) || antigas.isEmpty()) {
                Log.i(TAG, "Horário: primeira execução — semeando ${novas.size} aulas SEM notificar")
                CacheHelper.saveAulasCache(novas)
                SyncBaseline.markEstablished(ctx, SyncBaseline.AULAS)
                return false
            }

            // 4) Comparação real (multiconjunto normalizado).
            val diferencas = descreverDiferencas(antigas, novas)
            if (diferencas.isEmpty()) {
                Log.d(TAG, "Horário: idêntico ao cache (${novas.size} aulas) — sem notificação")
                return false
            }

            Log.i(TAG, "Horário: ${diferencas.size} diferença(s) candidata(s):")
            diferencas.take(12).forEach { Log.i(TAG, "   • $it") }

            // 5) ★ BUG 81: confirmação por re-leitura.
            // Se o portal/core devolve dados diferentes em duas chamadas seguidas,
            // a "mudança" é ruído da fonte, não um horário novo de verdade.
            val confirmacao = AulasRepository.fetchAulasComNomesDetalhado()
            if (confirmacao.aulas.isEmpty() || !confirmacao.nomesResolvidos) {
                Log.w(TAG, "Horário: confirmação falhou (dados nulos) — sem notificação")
                return false
            }

            val instavel = descreverDiferencas(novas, confirmacao.aulas)
            if (instavel.isNotEmpty()) {
                Log.w(
                    TAG,
                    "Horário: FONTE INSTÁVEL — duas leituras seguidas divergem " +
                            "(${instavel.size} diferença(s)). Notificação suprimida."
                )
                instavel.take(6).forEach { Log.w(TAG, "   ~ $it") }
                return false
            }

            CacheHelper.saveAulasCache(confirmacao.aulas)
            Log.i(TAG, "Horário: alteração CONFIRMADA em duas leituras — notificando")
            true
        } catch (e: SessionExpiredException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Erro em novoHorario", e)
            false
        }
    }

    // ------------------------------------------------------------------
    // Comparação
    // ------------------------------------------------------------------

    /**
     * Compara as duas listas como MULTICONJUNTO de aulas normalizadas:
     * ignora a ordem, mas detecta duplicatas, remoções e qualquer mudança
     * real de dia / código / nome / sala / horário.
     *
     * Devolve a lista legível de diferenças (vazia = idênticas).
     */
    private fun descreverDiferencas(antigas: List<Aula>, novas: List<Aula>): List<String> {
        val contagemAntigas = antigas.groupingBy { chaveDiff(it) }.eachCount()
        val contagemNovas = novas.groupingBy { chaveDiff(it) }.eachCount()

        if (contagemAntigas == contagemNovas) return emptyList()

        val diferencas = mutableListOf<String>()

        (contagemNovas.keys - contagemAntigas.keys).forEach { chave ->
            diferencas.add("ENTROU  [$chave] x${contagemNovas.getValue(chave)}")
        }
        (contagemAntigas.keys - contagemNovas.keys).forEach { chave ->
            diferencas.add("SAIU    [$chave] x${contagemAntigas.getValue(chave)}")
        }
        contagemNovas.keys.intersect(contagemAntigas.keys).forEach { chave ->
            val antes = contagemAntigas.getValue(chave)
            val agora = contagemNovas.getValue(chave)
            if (antes != agora) diferencas.add("QTDE    [$chave] $antes → $agora")
        }
        return diferencas
    }

    /** Identidade normalizada: diferenças cosméticas não viram "novo horário". */
    private fun chaveDiff(aula: Aula): String = listOf(
        DiasSemana.normalizar(aula.diaSemana).trim().lowercase(),
        aula.codigoDisciplina.trim().lowercase(),
        limparEspacos(aula.nomeDisciplina).lowercase(),
        limparEspacos(aula.sala).lowercase(),
        horaNormalizada(aula.horaInicio),
        horaNormalizada(aula.horaFim)
    ).joinToString("|")

    private fun limparEspacos(raw: String): String =
        raw.trim().replace(Regex("\\s+"), " ")

    /** "7:00" e "07:00" são o mesmo horário. */
    private fun horaNormalizada(raw: String): String {
        val match = Regex("(\\d{1,2}):(\\d{2})").find(raw) ?: return limparEspacos(raw)
        return "${match.groupValues[1].padStart(2, '0')}:${match.groupValues[2]}"
    }
}