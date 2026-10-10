package com.marinov.openfei.data

import android.content.Context
import android.util.Log
import android.webkit.CookieManager
import androidx.core.content.edit
import com.marinov.openfei.R
import com.marinov.openfei.core.CoreCookie
import com.marinov.openfei.core.CoreResult
import com.marinov.openfei.core.OpenFeiCore
import com.marinov.openfei.ui.login.LoginActivity
import com.marinov.openfei.util.WebViewHelper
import kotlinx.coroutines.Dispatchers
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

    suspend fun performLogin(user: String, pass: String, context: Context): LoginResult =
        withContext(Dispatchers.IO) {
            try {
                WebViewHelper.ensureWebView(context)
                val cookieManager = CookieManager.getInstance()
                cookieManager.setAcceptCookie(true)
                cookieManager.removeAllCookies(null)
                cookieManager.flush()
                Log.d(TAG, "Cookies antigos removidos")

                when (val result = OpenFeiCore.login(user, pass)) {
                    is CoreResult.Success -> {
                        val loginData = result.data
                        if (loginData.success) {
                            injectCookies(loginData.cookies)
                            val prefs = LoginActivity.getEncryptedPrefs(context)
                            prefs.edit {
                                putBoolean(LoginActivity.KEY_IS_LOGGED_IN, true)
                                putString(LoginActivity.KEY_USER, user)
                                putString(LoginActivity.KEY_PASS, pass)
                            }
                            Log.d(TAG, "Login FEI realizado com sucesso via Rust")
                            LoginResult(true)
                        } else {
                            val prefs = LoginActivity.getEncryptedPrefs(context)
                            prefs.edit { putBoolean(LoginActivity.KEY_IS_LOGGED_IN, false) }
                            Log.w(TAG, "Login falhou: ${loginData.errorMessage}")
                            LoginResult(
                                success = false,
                                errorMessage = loginData.errorMessage.ifEmpty {
                                    context.getString(R.string.login_credenciais_invalidas)
                                },
                                isNetworkError = loginData.isNetworkError
                            )
                        }
                    }
                    is CoreResult.Error -> {
                        Log.e(TAG, "Erro no login via Rust: ${result.code} - ${result.message}")
                        LoginResult(
                            success = false,
                            errorMessage = context.getString(R.string.login_erro_conexao, result.message),
                            isNetworkError = result.code == "NETWORK"
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Erro no login", e)
                LoginResult(
                    success = false,
                    errorMessage = context.getString(R.string.login_erro_conexao, e.message ?: ""),
                    isNetworkError = true
                )
            }
        }

    /**
     * ★ CORREÇÃO CRÍTICA PARA ANDROID 7/8:
     * WebViews antigos (Chrome < 80) NÃO reconhecem o atributo "SameSite"
     * e rejeitam o cookie INTEIRO silenciosamente quando ele está presente.
     * O Moodle envia "SameSite=None" em todos os cookies de sessão, fazendo com que
     * o CookieManager do Android 7/8 ignore completamente o "MoodleSession" ao chamar
     * setCookie(). Isso causa o loop infinito de login no Moodle.
     *
     * Esta função remove "SameSite" (qualquer valor) e "Partitioned" antes de injetar.
     */
    private fun injectCookies(cookies: List<CoreCookie>) {
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        var injectedCount = 0
        val seenCookies = mutableSetOf<String>()

        cookies.forEach { cookie ->
            if (!isAllowedDomain(cookie.origin)) {
                return@forEach
            }

            val domain = extractDomain(cookie.origin)
            if (domain.isEmpty()) return@forEach

            val targetUrl = "https://$domain"

            val cookieParts = cookie.cookieLine.split(";").map { it.trim() }
            val nameValue = cookieParts.firstOrNull() ?: return@forEach
            val cookieName = nameValue.substringBefore("=").trim()

            val attrs = mutableListOf<String>()
            var hasDomain = false
            var hasPath = false

            for (i in 1 until cookieParts.size) {
                val part = cookieParts[i]
                val lower = part.lowercase()
                when {
                    lower.startsWith("domain=") -> {
                        hasDomain = true
                        attrs.add(part)
                    }
                    lower.startsWith("path=") -> {
                        hasPath = true
                        attrs.add(part)
                    }
                    lower == "secure" -> {
                        attrs.add(part)
                    }
                    // ★ CORREÇÃO: Remove SameSite (qualquer valor) e Partitioned
                    // Esses atributos modernos não são suportados em WebViews do
                    // Android 7/8 e causam rejeição silenciosa do cookie inteiro
                    lower.startsWith("samesite=") -> {
                        // Ignora — remove do cookie
                    }
                    lower == "partitioned" -> {
                        // Ignora — atributo CHIPS moderno
                    }
                    else -> {
                        // Mantém outros atributos (HttpOnly, expires, Max-Age, etc.)
                        attrs.add(part)
                    }
                }
            }

            if (!hasDomain) {
                attrs.add("Domain=.$domain")
            }
            if (!hasPath) {
                attrs.add("Path=/")
            }

            val finalCookie = "$nameValue; ${attrs.joinToString("; ")}"

            val dedup = "$targetUrl|$finalCookie"
            if (seenCookies.contains(dedup)) return@forEach
            seenCookies.add(dedup)

            // Limpa versões antigas do mesmo cookie antes de injetar
            if (cookieName.isNotEmpty()) {
                limparCookieAntigo(targetUrl, domain, cookieName)
            }

            try {
                cookieManager.setCookie(targetUrl, finalCookie)
                injectedCount++
                Log.d(TAG, "Cookie injetado [$targetUrl]: ${finalCookie.take(120)}")
            } catch (e: Exception) {
                Log.e(TAG, "Erro ao injetar cookie em $targetUrl", e)
            }
        }

        cookieManager.flush()
        Log.d(TAG, "★ Total de cookies injetados: $injectedCount de ${cookies.size} recebidos do Rust")

        // Log de verificação
        val moodleCookies = cookieManager.getCookie("https://moodle.fei.edu.br")
        Log.d(TAG, "Cookies FINAIS no CookieManager para Moodle: ${moodleCookies ?: "NENHUM"}")
    }

    private fun limparCookieAntigo(targetUrl: String, domain: String, name: String) {
        val cookieManager = CookieManager.getInstance()
        val expirado = "Path=/; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT"

        cookieManager.setCookie(targetUrl, "$name=; Domain=$domain; $expirado")
        cookieManager.setCookie(targetUrl, "$name=; Domain=.$domain; $expirado")
        cookieManager.setCookie(targetUrl, "$name=; $expirado")
    }

    private fun extractDomain(url: String): String {
        return try {
            val uri = URI(url)
            uri.host ?: ""
        } catch (_: Exception) {
            ""
        }
    }

    private fun isAllowedDomain(url: String): Boolean {
        return try {
            val host = URI(url).host ?: return false
            ALLOWED_DOMAINS.any { host.endsWith(it) }
        } catch (_: Exception) {
            false
        }
    }

    suspend fun performLoginSilent(context: Context): LoginResult {
        val prefs = try {
            LoginActivity.getEncryptedPrefs(context)
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao acessar credenciais salvas", e)
            return LoginResult(false, "Erro ao acessar credenciais", isNetworkError = false)
        }
        val user = prefs.getString(LoginActivity.KEY_USER, "") ?: ""
        val pass = prefs.getString(LoginActivity.KEY_PASS, "") ?: ""
        if (user.isEmpty() || pass.isEmpty()) {
            Log.d(TAG, "Sem credenciais salvas — login silencioso impossível")
            return LoginResult(false, "Sem credenciais salvas", isNetworkError = false)
        }
        return performLogin(user, pass, context)
    }

    suspend fun performMoodleLoginSeparate(user: String, pass: String): MoodleLoginResult =
        withContext(Dispatchers.IO) {
            when (val result = OpenFeiCore.moodleLogin(user, pass)) {
                is CoreResult.Success -> {
                    val tokenData = result.data
                    if (tokenData.success) {
                        if (tokenData.cookies.isNotEmpty()) {
                            injectCookies(tokenData.cookies)
                            Log.d(TAG, "Cookies do Moodle injetados: ${tokenData.cookies.size} cookies")
                        }
                        MoodleLoginResult(
                            success = true,
                            errorMessage = "",
                            isNetworkError = false,
                            token = tokenData.token
                        )
                    } else {
                        MoodleLoginResult(
                            success = false,
                            errorMessage = tokenData.errorMessage,
                            isNetworkError = tokenData.isNetworkError,
                            token = null
                        )
                    }
                }
                is CoreResult.Error -> {
                    MoodleLoginResult(
                        success = false,
                        errorMessage = result.message,
                        isNetworkError = result.code == "NETWORK",
                        token = null
                    )
                }
            }
        }

    suspend fun forcarLoginCookiesMoodle(context: Context): MoodleLoginResult {
        val prefs = try {
            LoginActivity.getEncryptedPrefs(context)
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao acessar credenciais salvas", e)
            return MoodleLoginResult(false, "Erro ao acessar credenciais", isNetworkError = false)
        }
        val user = prefs.getString(LoginActivity.KEY_USER, "") ?: ""
        val pass = prefs.getString(LoginActivity.KEY_PASS, "") ?: ""
        if (user.isEmpty() || pass.isEmpty()) {
            Log.d(TAG, "Sem credenciais salvas — impossível forçar login de cookies do Moodle")
            return MoodleLoginResult(false, "Sem credenciais salvas", isNetworkError = false)
        }
        Log.d(TAG, "Forçando login completo do Moodle via Rust")
        return performMoodleLoginSeparate(user, pass)
    }
}