package com.marinov.openfei.login

import android.content.Context
import android.util.Log
import android.webkit.CookieManager
import com.marinov.openfei.BuildConfig
import com.marinov.openfei.R
import com.marinov.openfei.core.CoreCookie
import com.marinov.openfei.core.CoreResult
import com.marinov.openfei.core.OpenFeiCore
import com.marinov.openfei.util.WebViewHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.URI

data class LoginResult(
    val success: Boolean,
    val errorMessage: String = "",
    val isNetworkError: Boolean = false
)

data class MoodleLoginResult(
    val success: Boolean,
    val errorMessage: String = "",
    val isNetworkError: Boolean = false,
    val token: String? = null
)

object LoginLogic {

    private const val TAG = "LoginLogic"

    private val ALLOWED_DOMAINS = listOf(
        "interage.fei.org.br",
        "fei.org.br",
        "fei.edu.br",
        "moodle.fei.edu.br"
    )

    /** ★ BUG 3: hosts da FEI. Nunca inclui o Moodle. */
    private val FEI_COOKIE_URLS = listOf(
        "https://interage.fei.org.br",
        "https://fei.org.br",
        "https://www.fei.org.br"
    )

    private fun debugLog(msg: String) {
        if (BuildConfig.DEBUG) Log.d(TAG, msg)
    }

    /**
     * Login explícito (tela de login). A renovação periódica NÃO passa mais por aqui
     * — ela usa RustSession.ensureFeiSession(), que não mexe nos cookies do Moodle.
     */
    suspend fun performLogin(user: String, pass: String, context: Context): LoginResult {
        val app = context.applicationContext

        // ★ BUG 63: garante o WebView na main thread ANTES de tocar no CookieManager.
        withContext(Dispatchers.Main) { WebViewHelper.ensureWebView(app) }

        return withContext(Dispatchers.IO) {
            SessionLock.mutex.withLock {
                try {
                    val cm = CookieManager.getInstance()
                    cm.setAcceptCookie(true)
                    // ★ BUG 3: era removeAllCookies() — matava a sessão do Moodle.
                    clearFeiCookies()

                    when (val result = OpenFeiCore.login(user, pass)) {
                        is CoreResult.Success -> {
                            val data = result.data
                            if (data.success) {
                                injectCookies(data.cookies)
                                CredentialsStore.saveCredentials(app, user, pass)
                                RustSession.markLoggedIn()
                                debugLog("Login FEI OK")
                                LoginResult(true)
                            } else {
                                // ★ BUG 2: só desloga quando NÃO é erro de rede.
                                if (!data.isNetworkError) {
                                    CredentialsStore.setLoggedIn(app, false)
                                }
                                LoginResult(
                                    success = false,
                                    errorMessage = data.errorMessage.ifEmpty {
                                        app.getString(R.string.login_credenciais_invalidas)
                                    },
                                    isNetworkError = data.isNetworkError
                                )
                            }
                        }

                        is CoreResult.Error -> {
                            Log.e(TAG, "Erro no login: ${result.code}")
                            LoginResult(
                                success = false,
                                errorMessage = app.getString(
                                    R.string.login_erro_conexao,
                                    result.message
                                ),
                                isNetworkError = true
                            )
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Erro no login", e)
                    LoginResult(
                        success = false,
                        errorMessage = app.getString(
                            R.string.login_erro_conexao,
                            e.message ?: ""
                        ),
                        isNetworkError = true
                    )
                }
            }
        }
    }

    /**
     * ★ CORREÇÃO ANDROID 7/8 (mantida): WebViews antigos rejeitam o cookie inteiro
     * quando ele contém "SameSite"/"Partitioned". Removemos esses atributos.
     * ★ BUGS 66/67: logs de cookies só em DEBUG e sem valores.
     */
    internal fun injectCookies(cookies: List<CoreCookie>) {
        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)

        var injected = 0
        val seen = mutableSetOf<String>()

        cookies.forEach { cookie ->
            if (!isAllowedDomain(cookie.origin)) return@forEach
            val domain = extractDomain(cookie.origin)
            if (domain.isEmpty()) return@forEach

            val targetUrl = "https://$domain"
            val parts = cookie.cookieLine.split(";").map { it.trim() }
            val nameValue = parts.firstOrNull() ?: return@forEach
            val cookieName = nameValue.substringBefore("=").trim()

            val attrs = mutableListOf<String>()
            var hasDomain = false
            var hasPath = false

            for (i in 1 until parts.size) {
                val part = parts[i]
                val lower = part.lowercase()
                when {
                    lower.startsWith("domain=") -> { hasDomain = true; attrs.add(part) }
                    lower.startsWith("path=") -> { hasPath = true; attrs.add(part) }
                    lower == "secure" -> attrs.add(part)
                    lower.startsWith("samesite=") -> Unit
                    lower == "partitioned" -> Unit
                    else -> attrs.add(part)
                }
            }
            if (!hasDomain) attrs.add("Domain=.$domain")
            if (!hasPath) attrs.add("Path=/")

            val finalCookie = "$nameValue; ${attrs.joinToString("; ")}"
            val dedup = "$targetUrl|$finalCookie"
            if (!seen.add(dedup)) return@forEach

            if (cookieName.isNotEmpty()) expireCookie(targetUrl, domain, cookieName)

            runCatching {
                cm.setCookie(targetUrl, finalCookie)
                injected++
            }.onFailure { Log.e(TAG, "Erro ao injetar cookie em $domain") }
        }

        cm.flush()
        debugLog("Cookies injetados: $injected/${cookies.size}")
    }

    /** ★ BUG 3: expira apenas cookies dos domínios da FEI. */
    internal fun clearFeiCookies() {
        val cm = CookieManager.getInstance()
        FEI_COOKIE_URLS.forEach { url ->
            val existing = cm.getCookie(url) ?: return@forEach
            val host = extractDomain(url)
            existing.split(";")
                .map { it.trim().substringBefore("=") }
                .filter { it.isNotEmpty() }
                .forEach { name -> expireCookie(url, host, name) }
        }
        cm.flush()
    }

    private fun expireCookie(targetUrl: String, domain: String, name: String) {
        val cm = CookieManager.getInstance()
        val expired = "Path=/; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT"
        cm.setCookie(targetUrl, "$name=; $expired")
        cm.setCookie(targetUrl, "$name=; Domain=$domain; $expired")
        cm.setCookie(targetUrl, "$name=; Domain=.$domain; $expired")
    }

    private fun extractDomain(url: String): String =
        runCatching { URI(url).host.orEmpty() }.getOrDefault("")

    private fun isAllowedDomain(url: String): Boolean = runCatching {
        val host = URI(url).host ?: return false
        ALLOWED_DOMAINS.any { host.endsWith(it) }
    }.getOrDefault(false)

    suspend fun performMoodleLoginSeparate(user: String, pass: String): MoodleLoginResult =
        withContext(Dispatchers.IO) {
            when (val result = OpenFeiCore.moodleLogin(user, pass)) {
                is CoreResult.Success -> {
                    val data = result.data
                    if (data.success) {
                        if (data.cookies.isNotEmpty()) injectCookies(data.cookies)
                        MoodleLoginResult(true, token = data.token)
                    } else {
                        MoodleLoginResult(
                            success = false,
                            errorMessage = data.errorMessage,
                            isNetworkError = data.isNetworkError
                        )
                    }
                }

                is CoreResult.Error -> MoodleLoginResult(
                    success = false,
                    errorMessage = result.message,
                    isNetworkError = true
                )
            }
        }

    suspend fun forcarLoginCookiesMoodle(context: Context): MoodleLoginResult {
        val app = context.applicationContext
        val user = CredentialsStore.user(app)
        val pass = CredentialsStore.pass(app)
        if (user.isEmpty() || pass.isEmpty()) {
            return MoodleLoginResult(false, "Sem credenciais salvas")
        }
        return performMoodleLoginSeparate(user, pass)
    }
}