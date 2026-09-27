package com.marinov.openfei.data

import android.content.Context
import android.util.Log
import com.marinov.openfei.core.CoreResult
import com.marinov.openfei.core.OpenFeiCore
import com.marinov.openfei.ui.login.LoginActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

object RustSession {
    private const val TAG = "RustSession"
    private lateinit var appContext: Context
    private var lastFeiLoginTime = 0L
    private const val FEI_RENEW_INTERVAL_MS = 10L * 60 * 1000L // 10 minutos
    private val loginMutex = Mutex()

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    // ★ NOVO: Invalida a sessão da FEI para forçar um novo login na próxima requisição.
    // Usado quando o cliente HTTP do Rust é resetado (ex.: para limpar cookies velhos do Moodle).
    fun resetFeiSession() {
        lastFeiLoginTime = 0L
        Log.d(TAG, "Sessão FEI invalidada no RustSession (cliente HTTP resetado)")
    }

    suspend fun ensureFeiSession(): Boolean = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (now - lastFeiLoginTime < FEI_RENEW_INTERVAL_MS) {
            return@withContext true
        }
        loginMutex.withLock {
            val nowInside = System.currentTimeMillis()
            if (nowInside - lastFeiLoginTime < FEI_RENEW_INTERVAL_MS) {
                return@withLock true
            }
            val prefs = runCatching {
                LoginActivity.getEncryptedPrefs(appContext)
            }.getOrNull()
            if (prefs == null) {
                Log.e(TAG, "Não conseguiu acessar credenciais criptografadas")
                return@withLock false
            }
            val user = prefs.getString(LoginActivity.KEY_USER, "") ?: ""
            val pass = prefs.getString(LoginActivity.KEY_PASS, "") ?: ""
            if (user.isEmpty() || pass.isEmpty()) {
                Log.w(TAG, "Sem credenciais salvas para login Rust")
                return@withLock false
            }
            when (val result = OpenFeiCore.login(user, pass)) {
                is CoreResult.Success -> {
                    val loginData = result.data
                    if (loginData.success) {
                        lastFeiLoginTime = System.currentTimeMillis()
                        Log.d(TAG, "Login FEI via Rust realizado com sucesso")
                        true
                    } else {
                        Log.w(TAG, "Login FEI via Rust falhou: ${loginData.errorMessage}")
                        false
                    }
                }
                is CoreResult.Error -> {
                    Log.e(TAG, "Erro no login FEI via Rust: ${result.code} - ${result.message}")
                    false
                }
            }
        }
    }
}