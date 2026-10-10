package com.marinov.openfei.data

import android.content.Context
import android.util.Log
import com.marinov.openfei.core.CoreResult
import com.marinov.openfei.core.OpenFeiCore
import com.marinov.openfei.ui.login.LoginActivity
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
                val provas = fetchProvasFEIFromRust()
                Log.d(TAG, "obterProvasFEI online sucesso | size=${provas.size}")
                CacheHelper.saveProvasFEICache(provas)
                provas
            } catch (e: SessionExpiredException) {
                Log.w(TAG, "obterProvasFEI online: sessão expirada", e)
                throw e
            } catch (e: Exception) {
                if (e !is CancellationException) {
                    Log.e(TAG, "Erro ao buscar provas FEI online", e)
                }
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

    suspend fun obterProvasFEIOnlineOrNull(): List<ProvaCalendario>? {
        return try {
            val provas = fetchProvasFEIFromRust()
            Log.d(TAG, "obterProvasFEIOnlineOrNull sucesso | size=${provas.size}")
            CacheHelper.saveProvasFEICache(provas)
            provas
        } catch (e: SessionExpiredException) {
            Log.w(TAG, "obterProvasFEIOnlineOrNull: sessão expirada", e)
            null
        } catch (e: Exception) {
            if (e !is CancellationException) {
                Log.e(TAG, "obterProvasFEIOnlineOrNull: erro", e)
            }
            null
        }
    }

    suspend fun obterCalendarioProvas(online: Boolean): List<ProvaCalendario> {
        return obterProvasFEI(online)
    }

    fun obterCalendarioProvasCache(): List<ProvaCalendario> =
        CacheHelper.getCachedProvasFEI()

    fun obterProvasFEICache(): List<ProvaCalendario> =
        CacheHelper.getCachedProvasFEI()

    suspend fun obterEventosMoodle(online: Boolean): List<ProvaCalendario> {
        Log.d(TAG, "obterEventosMoodle | online=$online")
        return if (online) {
            try {
                val eventos = fetchEventosMoodleFromRust()
                Log.d(TAG, "obterEventosMoodle online sucesso | size=${eventos.size}")
                CacheHelper.saveEventosMoodleCache(eventos)
                eventos
            } catch (e: SessionExpiredException) {
                Log.w(TAG, "obterEventosMoodle online: sessão expirada", e)
                throw e
            } catch (e: Exception) {
                if (e !is CancellationException) {
                    Log.e(TAG, "Erro ao buscar eventos Moodle online", e)
                }
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
            Log.d(TAG, "obterEventosMoodleOnlineOrNull sucesso | size=${eventos.size}")
            CacheHelper.saveEventosMoodleCache(eventos)
            eventos
        } catch (e: SessionExpiredException) {
            Log.w(TAG, "obterEventosMoodleOnlineOrNull: sessão expirada", e)
            null
        } catch (e: Exception) {
            if (e !is CancellationException) {
                Log.e(TAG, "obterEventosMoodleOnlineOrNull: erro", e)
            }
            null
        }
    }

    fun obterEventosMoodleCache(): List<ProvaCalendario> =
        CacheHelper.getCachedEventosMoodle()

    private suspend fun fetchProvasFEIFromRust(): List<ProvaCalendario> = withContext(Dispatchers.IO) {
        // ★ CORREÇÃO: Garante sessão FEI antes de buscar provas
        if (!RustSession.ensureFeiSession()) {
            throw SessionExpiredException("Não foi possível garantir sessão FEI para provas")
        }

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
        val prefs = try {
            LoginActivity.getEncryptedPrefs(appContext)
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao acessar credenciais salvas", e)
            throw SessionExpiredException("Erro ao acessar credenciais")
        }

        val user = prefs.getString(LoginActivity.KEY_USER, "") ?: ""
        val pass = prefs.getString(LoginActivity.KEY_PASS, "") ?: ""

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