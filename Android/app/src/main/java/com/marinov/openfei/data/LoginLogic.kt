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
        "moodle.fei.edu.br"  // ★ Adicionado para permitir cookies do Moodle
    )

    /**
     * Login principal - APENAS para o servidor FEI (interage.fei.edu.br)
     * Usa Rust para fazer o login
     */
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
     * ★ CRÍTICO: Injeta cookies no CookieManager do Android
     * Remove o atributo 'Secure' para evitar erros em HTTP
     */
    private fun injectCookies(cookies: List<CoreCookie>) {
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)

        var injectedCount = 0
        val seenCookies = mutableSetOf<String>()
        val clearedNames = mutableSetOf<String>()

        cookies.forEach { cookie ->
            if (!isAllowedDomain(cookie.origin)) {
                return@forEach
            }

            val domain = extractDomain(cookie.origin)
            if (domain.isEmpty()) return@forEach

            // Sempre injetar em HTTPS (o Moodle usa HTTPS)
            val targetUrl = "https://$domain"

            // Parseia o cookie para garantir que tenha Domain e Path corretos
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
                        // ★ MANTÉM o atributo Secure! O Moodle exige para HTTPS ★
                        attrs.add(part)
                    }
                    else -> attrs.add(part)
                }
            }

            if (!hasDomain) {
                // ★ Adiciona Domain com o ponto na frente para cobrir subdomínios ★
                attrs.add("Domain=.$domain")
            }
            if (!hasPath) {
                attrs.add("Path=/")
            }

            val finalCookie = "$nameValue; ${attrs.joinToString("; ")}"

            // Evitar duplicatas
            val dedup = "$targetUrl|$finalCookie"
            if (seenCookies.contains(dedup)) return@forEach
            seenCookies.add(dedup)

            // ★ CORREÇÃO DO LOOP: antes de gravar o primeiro valor de um
            // cookie de nome X nesta leva, limpa qualquer resquício antigo
            // de X que porventura já esteja no CookieManager sob uma
            // variante de Domain diferente (ex.: sem o ponto no início,
            // guardado pelo próprio WebView numa navegação anterior). Por
            // RFC 6265, dois cookies de mesmo nome mas Domain diferente
            // coexistem e são enviados JUNTOS nas próximas requisições —
            // foi isso que fazia o Moodle, ao receber duas MoodleSession na
            // mesma requisição, tratar a sessão como inválida e mandar o
            // WebView de volta para /login/ mesmo logo após uma renovação
            // "bem-sucedida".
            if (cookieName.isNotEmpty() && clearedNames.add("$domain|$cookieName")) {
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

        // Debug final
        val moodleCookies = cookieManager.getCookie("https://moodle.fei.edu.br")
        Log.d(TAG, "Cookies FINAIS no CookieManager para Moodle: ${moodleCookies ?: "NENHUM"}")
    }

    /**
     * ★ NOVO: expira um cookie de nome [name] em ambas as variantes de
     * Domain plausíveis (com e sem o ponto inicial) e sem Domain explícito
     * (host-only), cobrindo os formatos em que o WebView pode tê-lo
     * armazenado anteriormente.
     */
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

    /**
     * Login silencioso - APENAS para FEI (não faz Moodle)
     */
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

    /**
     * ★ CRÍTICO: Garante que há um token válido do Moodle
     * Usa Rust para fazer login e injeta os cookies
     */
    suspend fun garantirMoodleToken(context: Context): String? = withContext(Dispatchers.IO) {
        val prefs = try {
            LoginActivity.getEncryptedPrefs(context)
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao acessar credenciais salvas", e)
            return@withContext null
        }
        val user = prefs.getString(LoginActivity.KEY_USER, "") ?: ""
        val pass = prefs.getString(LoginActivity.KEY_PASS, "") ?: ""
        if (user.isEmpty() || pass.isEmpty()) {
            Log.d(TAG, "Sem credenciais salvas — impossível obter token do Moodle")
            return@withContext null
        }

        when (val result = OpenFeiCore.moodleToken(user, pass)) {
            is CoreResult.Success -> {
                val tokenData = result.data
                if (tokenData.success) {
                    // ★ CRÍTICO: Injetar cookies antes de retornar o token ★
                    if (tokenData.cookies.isNotEmpty()) {
                        injectCookies(tokenData.cookies)
                        Log.d(TAG, "Cookies do Moodle injetados com sucesso: ${tokenData.cookies.size} cookies")
                    }
                    tokenData.token
                } else {
                    Log.e(TAG, "Falha ao obter token do Moodle: ${tokenData.errorMessage}")
                    null
                }
            }
            is CoreResult.Error -> {
                Log.e(TAG, "Erro ao obter token do Moodle: ${result.code} - ${result.message}")
                null
            }
        }
    }

    /**
     * ★ CRÍTICO: Login separado para o Moodle usando Rust
     * Retorna também o token do Moodle se obtido com sucesso
     */
    suspend fun performMoodleLoginSeparate(user: String, pass: String): MoodleLoginResult =
        withContext(Dispatchers.IO) {
            when (val result = OpenFeiCore.moodleLogin(user, pass)) {
                is CoreResult.Success -> {
                    val tokenData = result.data
                    if (tokenData.success) {
                        // ★ CRÍTICO: Injetar cookies do Moodle ★
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