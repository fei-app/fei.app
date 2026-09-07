package com.marinov.openfei.data

import android.content.Context
import android.util.Log
import android.webkit.CookieManager
import androidx.core.content.edit
import com.google.gson.JsonParser
import com.marinov.openfei.R
import com.marinov.openfei.ui.login.LoginActivity
import com.marinov.openfei.util.WebViewHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Connection
import org.jsoup.Jsoup
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
    const val LOGIN_URL = "https://interage.fei.org.br/secureserver/portal"
    private const val HOME_URL = "https://interage.fei.org.br/secureserver/portal/graduacao/home"
    private const val MAX_REDIRECTS = 10
    private const val MOODLE_LOGIN_URL = "https://moodle.fei.edu.br/login/index.php"
    private const val MOODLE_DOMAIN = "moodle.fei.edu.br"
    private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 16; sdk_gphone64_x86_64 Build/BE2A.250530.026.D1; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/133.0.6943.137 Mobile Safari/537.36"

    private val ALLOWED_DOMAINS = listOf(
        "interage.fei.org.br",
        "fei.org.br",
        "fei.edu.br"
    )

    /**
     * Login principal - APENAS para o servidor FEI (interage.fei.edu.br)
     * NÃO faz mais login automático no Moodle
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

                val resGet = Jsoup.connect(LOGIN_URL)
                    .userAgent(USER_AGENT)
                    .method(Connection.Method.GET)
                    .execute()

                val docGet = resGet.parse()
                val token = docGet.select("input[name=__RequestVerificationToken]").`val`()

                if (token.isEmpty()) {
                    return@withContext LoginResult(false, context.getString(R.string.login_token_erro), isNetworkError = true)
                }

                val cookiesByOrigin = mutableMapOf<String, List<String>>()
                val initialCookieHeaders = resGet.headers("Set-Cookie")
                if (initialCookieHeaders.isNotEmpty()) {
                    cookiesByOrigin[LOGIN_URL] = initialCookieHeaders
                }

                var resPost = Jsoup.connect("$LOGIN_URL/")
                    .userAgent(USER_AGENT)
                    .data("__RequestVerificationToken", token)
                    .data("Usuario", user)
                    .data("Senha", pass)
                    .cookies(parseCookiesFromHeaders(initialCookieHeaders))
                    .method(Connection.Method.POST)
                    .followRedirects(false)
                    .execute()

                var currentUrl = resPost.url().toString()
                var reachedHome = currentUrl.startsWith(HOME_URL)
                var redirectCount = 0

                while (!reachedHome &&
                    resPost.statusCode() in 300..399 &&
                    redirectCount < MAX_REDIRECTS
                ) {
                    val setCookieHeaders = resPost.headers("Set-Cookie")
                    if (setCookieHeaders.isNotEmpty()) {
                        cookiesByOrigin.getOrPut(currentUrl) { mutableListOf() }
                            .let { (it as MutableList).addAll(setCookieHeaders) }
                    }

                    val location = resPost.header("Location")
                    if (location.isNullOrEmpty()) break

                    val nextUrl = if (location.startsWith("http", ignoreCase = true)) {
                        location
                    } else {
                        URI(currentUrl).resolve(location).toString()
                    }

                    resPost = Jsoup.connect(nextUrl)
                        .userAgent(USER_AGENT)
                        .cookies(parseCookiesFromHeaders(cookiesByOrigin.values.flatten()))
                        .method(Connection.Method.GET)
                        .followRedirects(false)
                        .execute()

                    currentUrl = resPost.url().toString()
                    redirectCount++

                    if (currentUrl.startsWith(HOME_URL)) {
                        reachedHome = true
                    }
                }

                val finalCookieHeaders = resPost.headers("Set-Cookie")
                if (finalCookieHeaders.isNotEmpty()) {
                    cookiesByOrigin.getOrPut(currentUrl) { mutableListOf() }
                        .let { (it as MutableList).addAll(finalCookieHeaders) }
                }

                val docPost = resPost.parse()
                val isSuccess = reachedHome && docPost.select("#btn-login").isEmpty()

                val prefs = LoginActivity.getEncryptedPrefs(context)
                if (isSuccess) {
                    var injectedCount = 0
                    cookiesByOrigin.forEach { (originUrl, cookieHeaders) ->
                        if (isAllowedDomain(originUrl)) {
                            cookieHeaders.forEach { cookieHeader ->
                                cookieManager.setCookie(originUrl, cookieHeader)
                                injectedCount++
                                Log.d(TAG, "Cookie injetado de $originUrl: ${cookieHeader.take(80)}...")
                            }
                        } else {
                            Log.d(TAG, "Cookie ignorado (domínio não permitido) de $originUrl")
                        }
                    }
                    cookieManager.flush()
                    Log.d(TAG, "Total de cookies injetados: $injectedCount")

                    prefs.edit {
                        putBoolean(LoginActivity.KEY_IS_LOGGED_IN, true)
                        putString(LoginActivity.KEY_USER, user)
                        putString(LoginActivity.KEY_PASS, pass)
                    }

                    Log.d(TAG, "Login FEI realizado com sucesso (chegou em $HOME_URL)")
                    // NÃO faz mais login automático no Moodle
                    return@withContext LoginResult(true)
                } else {
                    prefs.edit { putBoolean(LoginActivity.KEY_IS_LOGGED_IN, false) }
                    val errorMsg = docPost.select(".field-validation-error").text()
                    Log.w(TAG, "Login falhou (url final: $currentUrl): $errorMsg")
                    return@withContext LoginResult(
                        success = false,
                        errorMessage = errorMsg.ifEmpty { context.getString(R.string.login_credenciais_invalidas) },
                        isNetworkError = false
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Erro no login", e)
                return@withContext LoginResult(
                    success = false,
                    errorMessage = context.getString(R.string.login_erro_conexao, e.message ?: ""),
                    isNetworkError = true
                )
            }
        }

    /**
     * Login separado para o Moodle - chamado apenas quando necessário
     * Retorna também o token do Moodle se obtido com sucesso
     */
    suspend fun performMoodleLoginSeparate(user: String, pass: String): MoodleLoginResult =
        withContext(Dispatchers.IO) {
            try {
                val cookieManager = CookieManager.getInstance()
                cookieManager.setAcceptCookie(true)

                val resGet = Jsoup.connect(MOODLE_LOGIN_URL)
                    .userAgent(USER_AGENT)
                    .method(Connection.Method.GET)
                    .execute()

                val docGet = resGet.parse()
                val logintoken = docGet.select("input[name=logintoken]").`val`()
                val cookieHeaders = resGet.headers("Set-Cookie")

                if (logintoken.isEmpty()) {
                    Log.w(TAG, "Moodle: logintoken não encontrado — login no Moodle abortado")
                    return@withContext MoodleLoginResult(false, "logintoken não encontrado", isNetworkError = true)
                }

                cookieHeaders.forEach { cookieHeader ->
                    cookieManager.setCookie(MOODLE_LOGIN_URL, cookieHeader)
                }
                cookieManager.flush()

                val webViewCookiesStr = WebViewHelper.getCookiesSafely(MOODLE_LOGIN_URL)
                val webViewCookiesMap = webViewCookiesStr.split(";")
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .associate {
                        val idx = it.indexOf("=")
                        if (idx > 0) it.substring(0, idx) to it.substring(idx + 1)
                        else it to ""
                    }

                val reqPost = Jsoup.connect(MOODLE_LOGIN_URL)
                    .userAgent(USER_AGENT)
                    .data("anchor", "")
                    .data("logintoken", logintoken)
                    .data("username", user)
                    .data("password", pass)
                    .method(Connection.Method.POST)
                    .followRedirects(false)

                if (webViewCookiesMap.isNotEmpty()) {
                    reqPost.cookies(webViewCookiesMap)
                } else {
                    reqPost.cookies(parseCookiesFromHeaders(cookieHeaders))
                }

                val resPost = reqPost.execute()
                val statusCode = resPost.statusCode()
                val isSuccess = if (statusCode in 300..399) {
                    true
                } else {
                    val docPost = resPost.parse()
                    docPost.select("form#login").isEmpty()
                }

                if (isSuccess) {
                    val finalCookieHeaders = resPost.headers("Set-Cookie")
                    finalCookieHeaders.forEach { cookieHeader ->
                        cookieManager.setCookie(MOODLE_LOGIN_URL, cookieHeader)
                    }
                    cookieManager.flush()
                    Log.d(TAG, "Login no Moodle realizado com sucesso")

                    // Tenta obter o token
                    val token = try {
                        val tokenUrl = "https://$MOODLE_DOMAIN/login/token.php?username=$user&password=$pass&service=moodle_mobile_app"
                        val tokenResponse = Jsoup.connect(tokenUrl)
                            .userAgent(USER_AGENT)
                            .ignoreContentType(true)
                            .method(Connection.Method.GET)
                            .execute()
                        val json = tokenResponse.body()
                        val jsonObject = JsonParser.parseString(json).asJsonObject
                        if (jsonObject.has("token")) {
                            jsonObject.get("token").asString
                        } else {
                            null
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Não foi possível obter token do Moodle após login", e)
                        null
                    }

                    return@withContext MoodleLoginResult(true, token = token)
                } else {
                    Log.w(TAG, "Login no Moodle falhou — formulário presente na resposta (Status: $statusCode)")
                    return@withContext MoodleLoginResult(false, "Credenciais inválidas", isNetworkError = false)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Erro no login do Moodle", e)
                return@withContext MoodleLoginResult(false, e.message ?: "Erro desconhecido", isNetworkError = true)
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

        return try {
            performLogin(user, pass, context)
        } catch (e: Exception) {
            Log.e(TAG, "Login silencioso falhou com exceção", e)
            LoginResult(false, context.getString(R.string.login_erro_conexao, e.message ?: ""), isNetworkError = true)
        }
    }

    /**
     * Garante que há um token válido do Moodle
     * Se não houver token ou estiver expirado, faz login no Moodle
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

        // Primeiro tenta obter o token existente
        val existingToken = try {
            val url = "https://$MOODLE_DOMAIN/login/token.php?username=$user&password=$pass&service=moodle_mobile_app"
            val response = Jsoup.connect(url)
                .userAgent(USER_AGENT)
                .ignoreContentType(true)
                .method(Connection.Method.GET)
                .execute()
            val json = response.body()
            val jsonObject = JsonParser.parseString(json).asJsonObject
            if (jsonObject.has("token")) {
                jsonObject.get("token").asString
            } else {
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Erro ao tentar obter token existente do Moodle", e)
            null
        }

        if (existingToken != null) {
            Log.d(TAG, "Token do Moodle já existe e é válido")
            return@withContext existingToken
        }

        // Se não tem token válido, faz login no Moodle
        Log.d(TAG, "Token do Moodle não encontrado ou inválido, fazendo login...")
        val loginResult = performMoodleLoginSeparate(user, pass)

        if (loginResult.success) {
            Log.d(TAG, "Login no Moodle realizado com sucesso, token obtido")
            return@withContext loginResult.token
        } else {
            Log.e(TAG, "Falha ao fazer login no Moodle: ${loginResult.errorMessage}")
            return@withContext null
        }
    }

    /**
     * Obtém o token do Moodle (versão legada, mantida para compatibilidade)
     */
    suspend fun getMoodleToken(context: Context): String? = garantirMoodleToken(context)

    private fun parseCookiesFromHeaders(setCookieHeaders: List<String>): Map<String, String> {
        val cookies = mutableMapOf<String, String>()
        setCookieHeaders.forEach { header ->
            val parts = header.split(";").first().trim()
            val equalsIndex = parts.indexOf("=")
            if (equalsIndex > 0) {
                val name = parts.substring(0, equalsIndex)
                val value = parts.substring(equalsIndex + 1)
                cookies[name] = value
            }
        }
        return cookies
    }

    private fun isAllowedDomain(url: String): Boolean {
        return try {
            val host = URI(url).host ?: return false
            ALLOWED_DOMAINS.any { host.endsWith(it) }
        } catch (_: Exception) {
            false
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

        return performMoodleLoginSeparate(user, pass)
    }
}