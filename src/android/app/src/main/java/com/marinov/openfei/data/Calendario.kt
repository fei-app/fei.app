package com.marinov.openfei.data

import android.content.Context
import android.util.Log
import com.marinov.openfei.cache.CacheHelper
import com.marinov.openfei.cache.SyncBaseline
import com.marinov.openfei.core.CoreResult
import com.marinov.openfei.core.OpenFeiCore
import com.marinov.openfei.login.CredentialsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

object CalendarioRepository {
    private const val TAG = "CalendarioRepository"
    private lateinit var appContext: Context

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    suspend fun obterProvasFEI(online: Boolean): List<ProvaCalendario> {
        Log.d(TAG, "obterProvasFEI | online=$online")
        return if (online) {
            try {
                val provas = persistirProvas(fetchProvasFEIFromRust())
                Log.d(TAG, "obterProvasFEI online sucesso | size=${provas.size}")
                provas
            } catch (e: SessionExpiredException) {
                Log.w(TAG, "obterProvasFEI online: sessão expirada", e)
                throw e
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Erro ao buscar provas FEI online", e)
                val cache = CacheHelper.getCachedProvasFEI()
                Log.d(TAG, "obterProvasFEI fallback cache | size=${cache.size}")
                cache
            }
        } else {
            val cache = CacheHelper.getCachedProvasFEI()
            Log.d(TAG, "obterProvasFEI offline cache | size=${cache.size}")
            cache
        }
    }

    /**
     * ★ BUG 80: devolve null quando o online veio vazio e existe cache válido.
     * Isso mantém o contrato "online ou null" e faz o CalendarSyncManager
     * calcular allowDeleteMissing=false (não apaga a agenda com dados nulos).
     */
    suspend fun obterProvasFEIOnlineOrNull(): List<ProvaCalendario>? {
        return try {
            val provas = fetchProvasFEIFromRust()
            if (provas.isEmpty()) {
                val cache = CacheHelper.getCachedProvasFEI()
                if (cache.isNotEmpty()) {
                    Log.w(TAG, "Provas FEI vazias online — preservando cache (${cache.size})")
                    return null
                }
                Log.w(TAG, "Provas FEI: 0 registros online e cache vazio")
                CacheHelper.saveProvasFEICache(provas)
                return provas
            }
            Log.d(TAG, "obterProvasFEIOnlineOrNull sucesso | size=${provas.size}")
            CacheHelper.saveProvasFEICache(provas)
            marcarBaseline(SyncBaseline.PROVAS_FEI)
            provas
        } catch (e: SessionExpiredException) {
            Log.w(TAG, "obterProvasFEIOnlineOrNull: sessão expirada", e)
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "obterProvasFEIOnlineOrNull: erro", e)
            null
        }
    }

    fun obterProvasFEICache(): List<ProvaCalendario> =
        CacheHelper.getCachedProvasFEI()

    suspend fun obterEventosMoodle(online: Boolean): List<ProvaCalendario> {
        Log.d(TAG, "obterEventosMoodle | online=$online")
        return if (online) {
            try {
                val eventos = persistirEventos(fetchEventosMoodleFromRust())
                Log.d(TAG, "obterEventosMoodle online sucesso | size=${eventos.size}")
                eventos
            } catch (e: SessionExpiredException) {
                Log.w(TAG, "obterEventosMoodle online: sessão expirada", e)
                throw e
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Erro ao buscar eventos Moodle online", e)
                val cache = CacheHelper.getCachedEventosMoodle()
                Log.d(TAG, "obterEventosMoodle fallback cache | size=${cache.size}")
                cache
            }
        } else {
            val cache = CacheHelper.getCachedEventosMoodle()
            Log.d(TAG, "obterEventosMoodle offline cache | size=${cache.size}")
            cache
        }
    }

    suspend fun obterEventosMoodleOnlineOrNull(): List<ProvaCalendario>? {
        return try {
            val eventos = fetchEventosMoodleFromRust()
            if (eventos.isEmpty()) {
                val cache = CacheHelper.getCachedEventosMoodle()
                if (cache.isNotEmpty()) {
                    Log.w(TAG, "Eventos Moodle vazios online — preservando cache (${cache.size})")
                    return null
                }
                Log.w(TAG, "Eventos Moodle: 0 registros online e cache vazio")
                CacheHelper.saveEventosMoodleCache(eventos)
                return eventos
            }
            Log.d(TAG, "obterEventosMoodleOnlineOrNull sucesso | size=${eventos.size}")
            CacheHelper.saveEventosMoodleCache(eventos)
            marcarBaseline(SyncBaseline.EVENTOS_MOODLE)
            eventos
        } catch (e: SessionExpiredException) {
            Log.w(TAG, "obterEventosMoodleOnlineOrNull: sessão expirada", e)
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "obterEventosMoodleOnlineOrNull: erro", e)
            null
        }
    }

    fun obterEventosMoodleCache(): List<ProvaCalendario> =
        CacheHelper.getCachedEventosMoodle()

    // ------------------------------------------------------------------
    // ★ BUG 80: guards de dados nulos
    // ------------------------------------------------------------------
    private fun marcarBaseline(tipo: String) {
        if (::appContext.isInitialized) {
            runCatching { SyncBaseline.markEstablished(appContext, tipo) }
        }
    }

    private fun persistirProvas(novas: List<ProvaCalendario>): List<ProvaCalendario> {
        val cache = CacheHelper.getCachedProvasFEI()
        if (novas.isEmpty() && cache.isNotEmpty()) {
            Log.w(TAG, "Provas FEI vazias online — mantendo cache (${cache.size})")
            return cache
        }
        CacheHelper.saveProvasFEICache(novas)
        return novas
    }

    private fun persistirEventos(novos: List<ProvaCalendario>): List<ProvaCalendario> {
        val cache = CacheHelper.getCachedEventosMoodle()
        if (novos.isEmpty() && cache.isNotEmpty()) {
            Log.w(TAG, "Eventos Moodle vazios online — mantendo cache (${cache.size})")
            return cache
        }
        CacheHelper.saveEventosMoodleCache(novos)
        return novos
    }

    private suspend fun fetchProvasFEIFromRust(): List<ProvaCalendario> = withContext(Dispatchers.IO) {
        ensureFeiSessionOrThrow("provas FEI")
        when (val result = OpenFeiCore.fetchProvasFei()) {
            is CoreResult.Success -> {
                result.data.map { core ->
                    ProvaCalendario(
                        disciplina = core.disciplina,
                        nomeDisciplina = core.nomeDisciplina,
                        dataProva = core.dataProva,
                        hora = core.hora,
                        sala = core.sala,
                        coordenador = core.coordenador,
                        tipoProva = core.tipoProva
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

    private suspend fun fetchEventosMoodleFromRust(): List<ProvaCalendario> = withContext(Dispatchers.IO) {
        val user = CredentialsStore.user(appContext)
        val pass = CredentialsStore.pass(appContext)
        if (user.isEmpty() || pass.isEmpty()) {
            Log.d(TAG, "Sem credenciais salvas — impossível buscar eventos Moodle")
            throw SessionExpiredException("Sem credenciais salvas")
        }
        when (val result = OpenFeiCore.fetchEventosMoodle(user, pass)) {
            is CoreResult.Success -> {
                result.data.map { core ->
                    ProvaCalendario(
                        disciplina = core.disciplina,
                        nomeDisciplina = core.nomeDisciplina,
                        dataProva = core.dataProva,
                        hora = core.hora,
                        sala = core.sala,
                        coordenador = core.coordenador,
                        tipoProva = core.tipoProva
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