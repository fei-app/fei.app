package com.marinov.openfei.login

import kotlinx.coroutines.sync.Mutex

/**
 * ★ BUGS 18 e 19: havia dois mutexes independentes (RustSession e SessionManager)
 * e o LoginLogic não usava nenhum. Dois logins nativos simultâneos, ou um
 * clearSession() no meio de um fetch, corrompiam o estado de sessão.
 * Agora TODA operação de login/renovação/limpeza passa por este único lock.
 */
internal object SessionLock {
    val mutex = Mutex()
}