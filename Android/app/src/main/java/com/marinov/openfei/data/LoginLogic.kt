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
    val errorMessage: String = ""
)

object LoginLogic {
    private const val TAG = "LoginLogic"
    const val LOGIN_URL = "https://interage.fei.org.br/secureserver/portal"
    private const val HOME_URL = "https://interage.fei.org.br/secureserver/portal/graduacao/home"
    private const val MAX_REDIRECTS = 10

    private const val MOODLE_LOGIN_URL = "https://moodle.fei.edu.br/login/index.php"
    private const val MOODLE_DOMAIN = "moodle.fei.edu.br"

    private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 16; sdk_gphone64_x86_64 Build/BE2A.250530.026.D1; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/133.0.6943.137 Mobile Safari/537.36"

    // Domínios que devem ter seus cookies injetados no WebView
    private val ALLOWED_DOMAINS = listOf(
        "interage.fei.org.br",
        "fei.org.br",
        "fei.edu.br"
    )

    suspend fun performLogin(user: String, pass: String, context: Context): LoginResult =
        withContext(Dispatchers.IO) {
            try {
                WebViewHelper.ensureWebView(context)
                val cookieManager = CookieManager.getInstance()
                cookieManager.setAcceptCookie(true)

                // Rastreia cookies por URL de origem
                val cookiesByOrigin = mutableMapOf<String, MutableMap<String, String>>()

                val resGet = Jsoup.connect(LOGIN_URL)
                    .userAgent(USER_AGENT)
                    .method(Connection.Method.GET)
                    .execute()
                val docGet = resGet.parse()
                val token = docGet.select("input[name=__RequestVerificationToken]").`val`()

                if (token.isEmpty()) {
                    return@withContext LoginResult(false, context.getString(R.string.login_token_erro))
                }

                // Armazena cookies iniciais com sua URL de origem
                cookiesByOrigin[LOGIN_URL] = resGet.cookies().toMutableMap()

                var resPost = Jsoup.connect("$LOGIN_URL/")
                    .userAgent(USER_AGENT)
                    .data("__RequestVerificationToken", token)
                    .data("Usuario", user)
                    .data("Senha", pass)
                    .cookies(getAllCookies(cookiesByOrigin))
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
                    // Armazena cookies desta resposta com sua URL de origem
                    if (resPost.cookies().isNotEmpty()) {
                        cookiesByOrigin.getOrPut(currentUrl) { mutableMapOf() }
                            .putAll(resPost.cookies())
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
                        .cookies(getAllCookies(cookiesByOrigin))
                        .method(Connection.Method.GET)
                        .followRedirects(false)
                        .execute()

                    currentUrl = resPost.url().toString()
                    redirectCount++
                    if (currentUrl.startsWith(HOME_URL)) {
                        reachedHome = true
                    }
                }

                // Armazena cookies finais
                if (resPost.cookies().isNotEmpty()) {
                    cookiesByOrigin.getOrPut(currentUrl) { mutableMapOf() }
                        .putAll(resPost.cookies())
                }

                val docPost = resPost.parse()
                val isSuccess = reachedHome && docPost.select("#btn-login").isEmpty()
                val prefs = LoginActivity.getEncryptedPrefs(context)

                if (isSuccess) {
                    // ★ CORREÇÃO: Só injeta no CookieManager os cookies de domínios permitidos ★
                    cookiesByOrigin.forEach { (originUrl, cookies) ->
                        if (isAllowedDomain(originUrl)) {
                            cookies.forEach { (key, value) ->
                                cookieManager.setCookie(originUrl, "$key=$value")
                            }
                        }
                    }
                    cookieManager.flush()

                    prefs.edit {
                        putBoolean(LoginActivity.KEY_IS_LOGGED_IN, true)
                        putString(LoginActivity.KEY_USER, user)
                        putString(LoginActivity.KEY_PASS, pass)
                    }
                    Log.d(TAG, "Login realizado com sucesso (chegou em $HOME_URL)")

                    val moodleOk = performMoodleLogin(user, pass)
                    if (!moodleOk) {
                        Log.w(TAG, "Login no Moodle não pôde ser concluído — pode pedir login manual ao abrir")
                    }
                    return@withContext LoginResult(true)
                } else {
                    prefs.edit { putBoolean(LoginActivity.KEY_IS_LOGGED_IN, false) }
                    val errorMsg = docPost.select(".field-validation-error").text()
                    Log.w(TAG, "Login falhou (url final: $currentUrl): $errorMsg")
                    return@withContext LoginResult(
                        false,
                        errorMsg.ifEmpty { context.getString(R.string.login_credenciais_invalidas) }
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Erro no login", e)
                try {
                    LoginActivity.getEncryptedPrefs(context).edit {
                        putBoolean(LoginActivity.KEY_IS_LOGGED_IN, false)
                    }
                } catch (_: Exception) {}
                return@withContext LoginResult(
                    false,
                    context.getString(R.string.login_erro_conexao, e.message ?: "")
                )
            }
        }

    private fun getAllCookies(cookiesByOrigin: Map<String, Map<String, String>>): Map<String, String> {
        val allCookies = mutableMapOf<String, String>()
        cookiesByOrigin.values.forEach { allCookies.putAll(it) }
        return allCookies
    }

    private fun isAllowedDomain(url: String): Boolean {
        return try {
            val host = URI(url).host ?: return false
            ALLOWED_DOMAINS.any { host.endsWith(it) }
        } catch (e: Exception) {
            false
        }
    }

    private suspend fun performMoodleLogin(user: String, pass: String): Boolean =
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
                val cookiesGet = resGet.cookies()
                if (logintoken.isEmpty()) {
                    Log.w(TAG, "Moodle: logintoken não encontrado — login no Moodle abortado")
                    return@withContext false
                }

                cookiesGet.forEach { (key, value) ->
                    cookieManager.setCookie(MOODLE_LOGIN_URL, "$key=$value")
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
                    reqPost.cookies(cookiesGet)
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
                    resPost.cookies().forEach { (key, value) ->
                        cookieManager.setCookie(MOODLE_LOGIN_URL, "$key=$value")
                    }
                    cookieManager.flush()
                    Log.d(TAG, "Login no Moodle realizado com sucesso")
                } else {
                    Log.w(TAG, "Login no Moodle falhou — formulário presente na resposta (Status: $statusCode)")
                }
                isSuccess
            } catch (e: Exception) {
                Log.e(TAG, "Erro no login do Moodle", e)
                false
            }
        }

    suspend fun performLoginSilent(context: Context): Boolean {
        val prefs = try {
            LoginActivity.getEncryptedPrefs(context)
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao acessar credenciais salvas", e)
            return false
        }
        val user = prefs.getString(LoginActivity.KEY_USER, "") ?: ""
        val pass = prefs.getString(LoginActivity.KEY_PASS, "") ?: ""
        if (user.isEmpty() || pass.isEmpty()) {
            Log.d(TAG, "Sem credenciais salvas — login silencioso impossível")
            return false
        }
        return try {
            val result = performLogin(user, pass, context)
            result.success
        } catch (e: Exception) {
            Log.e(TAG, "Login silencioso falhou", e)
            false
        }
    }

    suspend fun getMoodleToken(context: Context): String? = withContext(Dispatchers.IO) {
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
        try {
            val url = "https://$MOODLE_DOMAIN/login/token.php?username=$user&password=$pass&service=moodle_mobile_app"
            val response = Jsoup.connect(url)
                .userAgent(USER_AGENT)
                .ignoreContentType(true)
                .method(Connection.Method.GET)
                .execute()
            val json = response.body()
            val jsonObject = JsonParser.parseString(json).asJsonObject
            if (jsonObject.has("token")) {
                return@withContext jsonObject.get("token").asString
            } else if (jsonObject.has("error")) {
                Log.e(TAG, "Erro ao obter token Moodle: ${jsonObject.get("error").asString}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exceção ao obter token Moodle", e)
        }
        return@withContext null
    }
}