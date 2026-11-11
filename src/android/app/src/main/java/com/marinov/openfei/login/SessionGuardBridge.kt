package com.marinov.openfei.login

import android.util.Log
import kotlinx.coroutines.runBlocking

/**
 * ★ PROBLEMA 3: ponte síncrona usada pelo OpenFeiCore para renovar a sessão
 * no meio de uma chamada nativa que retornou SESSION_EXPIRED.
 *
 * Só é chamada a partir de threads de background (Dispatchers.IO / Worker),
 * nunca da main thread.
 */
object SessionGuardBridge {

    private const val TAG = "SessionGuardBridge"

    fun isRenewing(): Boolean = RustSession.isRenewing

    fun renewBlocking(): Boolean {
        if (RustSession.isRenewing) return false
        return try {
            runBlocking {
                RustSession.invalidateCacheOnly()
                when (val r = RustSession.ensureFeiSession()) {
                    is SessionResult.Ok -> true
                    else -> {
                        Log.w(TAG, "Renovação falhou: ${r::class.simpleName}")
                        false
                    }
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Erro ao renovar sessão em background", t)
            false
        }
    }
}