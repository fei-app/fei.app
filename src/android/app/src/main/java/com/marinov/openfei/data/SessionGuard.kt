package com.marinov.openfei.data

import com.marinov.openfei.login.RustSession
import com.marinov.openfei.login.SessionResult
import java.io.IOException

/**
 * ★ BUG 1: ponto único de validação de sessão para todos os repositórios.
 *
 * - Ok            → segue
 * - NetworkError  → IOException  (a UI mostra offline / usa cache, NÃO desloga)
 * - AuthError     → SessionExpiredException (aí sim, login)
 * - NoCredentials → SessionExpiredException
 */
internal suspend fun ensureFeiSessionOrThrow(origem: String) {
    when (val result = RustSession.ensureFeiSession()) {
        is SessionResult.Ok -> Unit
        is SessionResult.NetworkError ->
            throw IOException("Sem conexão ao validar sessão FEI ($origem): ${result.message}")
        is SessionResult.AuthError ->
            throw SessionExpiredException(result.message)
        SessionResult.NoCredentials ->
            throw SessionExpiredException("Sem credenciais salvas ($origem)")
    }
}