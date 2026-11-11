package com.marinov.openfei.data

import android.util.Log
import com.marinov.openfei.cache.CacheHelper

/**
 * Detecção de mudanças no horário de aulas (usada pelo BackgroundService
 * para disparar a notificação de "horário alterado").
 */
object HorariosRepository {

    private const val TAG = "HorariosRepository"

    /**
     * Busca o horário online e compara com o cache.
     * Retorna true somente se houve alteração em relação ao cache existente.
     * Na primeira carga (cache vazio), apenas salva e retorna false.
     */
    suspend fun novoHorario(online: Boolean): Boolean {
        if (!online) return false
        return try {
            val novasComNomes = AulasRepository.fetchAulasComNomes()
            val antigasAulas = CacheHelper.getCachedAulas()
            if (antigasAulas.isEmpty()) {
                CacheHelper.saveAulasCache(novasComNomes)
                return false
            }
            val alterado = novasComNomes.toSet() != antigasAulas.toSet()
            if (alterado) CacheHelper.saveAulasCache(novasComNomes)
            alterado
        } catch (e: SessionExpiredException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Erro em novoHorario", e)
            false
        }
    }
}