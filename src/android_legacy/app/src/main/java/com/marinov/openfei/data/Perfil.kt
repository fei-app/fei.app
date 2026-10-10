package com.marinov.openfei.data

import android.util.Log
import com.marinov.openfei.core.CoreResult
import com.marinov.openfei.core.OpenFeiCore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

object PerfilRepository {
    private const val TAG = "PerfilRepository"

    suspend fun obterPerfilOnlineOrNull(): Perfil? {
        return try {
            val perfil = fetchPerfilFromRust()
            if (!perfilTemDados(perfil)) {
                Log.w(TAG, "Perfil online veio vazio. Mantendo cache atual.")
                return null
            }
            CacheHelper.savePerfilCache(perfil)
            perfil
        } catch (e: SessionExpiredException) {
            Log.w(TAG, "Sessão expirada ao buscar perfil", e)
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao buscar perfil online", e)
            null
        }
    }

    fun obterPerfilCache(): Perfil? {
        return CacheHelper.getCachedPerfil()
    }

    private fun perfilTemDados(perfil: Perfil): Boolean {
        return perfil.nome.isNotBlank() ||
                perfil.matricula.isNotBlank() ||
                perfil.curso.isNotBlank() ||
                perfil.email.isNotBlank()
    }

    private suspend fun fetchPerfilFromRust(): Perfil = withContext(Dispatchers.IO) {
        // ★ CORREÇÃO: Garante sessão FEI antes de buscar perfil
        if (!RustSession.ensureFeiSession()) {
            throw SessionExpiredException("Não foi possível garantir sessão FEI para perfil")
        }

        when (val result = OpenFeiCore.fetchPerfil()) {
            is CoreResult.Success -> {
                Perfil(
                    nome = result.data.nome,
                    matricula = result.data.matricula,
                    curso = result.data.curso,
                    email = result.data.email
                )
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