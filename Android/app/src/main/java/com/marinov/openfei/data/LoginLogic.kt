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

data class LoginResult(
    val success: Boolean,
    val errorMessage: String = ""
)

object LoginLogic {
    private const val TAG = "LoginLogic"
    const val LOGIN_URL = "https://interage.fei.org.br/secureserver/portal"

    // Configurações do Moodle
    private const val MOODLE_LOGIN_URL = "https://moodle.fei.edu.br/login/index.php"
    private const val MOODLE_DOMAIN = "moodle.fei.edu.br"

    // User Agent padronizado (mesmo do WebView) para não ser bloqueado por firewalls
    private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 16; sdk_gphone64_x86_64 Build/BE2A.250530.026.D1; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/133.0.6943.137 Mobile Safari/537.36"

    suspend fun performLogin(user: String, pass: String, context: Context): LoginResult =
        withContext(Dispatchers.IO) {
            try {
                WebViewHelper.ensureWebView(context)
                val resGet = Jsoup.connect(LOGIN_URL)
                    .userAgent(USER_AGENT)
                    .method(Connection.Method.GET)
                    .execute()
                val docGet = resGet.parse()
                val token = docGet.select("input[name=__RequestVerificationToken]").`val`()
                val cookies = resGet.cookies()
                if (token.isEmpty()) {
                    return@withContext LoginResult(false, context.getString(R.string.login_token_erro))
                }
                val resPost = Jsoup.connect("$LOGIN_URL/")
                    .userAgent(USER_AGENT)
                    .data("__RequestVerificationToken", token)
                    .data("Usuario", user)
                    .data("Senha", pass)
                    .cookies(cookies)
                    .method(Connection.Method.POST)
                    .followRedirects(true)
                    .execute()
                val docPost = resPost.parse()
                val isSuccess = docPost.select("#btn-login").isEmpty()
                val prefs = LoginActivity.getEncryptedPrefs(context)
                if (isSuccess) {
                    val cookieManager = CookieManager.getInstance()
                    cookieManager.setAcceptCookie(true)
                    resPost.cookies().forEach { (key, value) ->
                        cookieManager.setCookie(
                            "https://interage.fei.org.br",
                            "$key=$value; Domain=interage.fei.org.br; Path=/; Max-Age=900"
                        )
                    }
                    cookieManager.flush()
                    prefs.edit {
                        putBoolean(LoginActivity.KEY_IS_LOGGED_IN, true)
                        putString(LoginActivity.KEY_USER, user)
                        putString(LoginActivity.KEY_PASS, pass)
                    }
                    Log.d(TAG, "Login realizado com sucesso")
                    // Dispara o login no Moodle
                    val moodleOk = performMoodleLogin(user, pass)
                    if (!moodleOk) {
                        Log.w(TAG, "Login no Moodle não pôde ser concluído — pode pedir login manual ao abrir")
                    }
                    return@withContext LoginResult(true)
                } else {
                    prefs.edit { putBoolean(LoginActivity.KEY_IS_LOGGED_IN, false) }
                    val errorMsg = docPost.select(".field-validation-error").text()
                    Log.w(TAG, "Login falhou: $errorMsg")
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

    private suspend fun performMoodleLogin(user: String, pass: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val cookieManager = CookieManager.getInstance()
                cookieManager.setAcceptCookie(true)
                // 1. GET para pegar logintoken e MoodleSession não-autenticado
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
                // Sincroniza os cookies iniciais com o CookieManager (essencial para que o JS saiba depois)
                cookiesGet.forEach { (key, value) ->
                    cookieManager.setCookie("https://$MOODLE_DOMAIN", "$key=$value; Path=/")
                }
                cookieManager.flush()
                // Resgata a string de cookies exata que o WebView estaria utilizando
                val webViewCookies = WebViewHelper.getCookiesSafely(MOODLE_LOGIN_URL)
                // 2. POST para efetuar o login
                val reqPost = Jsoup.connect(MOODLE_LOGIN_URL)
                    .userAgent(USER_AGENT)
                    .data("anchor", "")
                    .data("logintoken", logintoken)
                    .data("username", user)
                    .data("password", pass)
                    .method(Connection.Method.POST)
                    .followRedirects(false) // CRUCIAL: 'false' para capturarmos os cookies atualizados do redirecionamento 303!
                if (webViewCookies.isNotEmpty()) {
                    reqPost.header("Cookie", webViewCookies)
                } else {
                    reqPost.cookies(cookiesGet)
                }
                val resPost = reqPost.execute()
                val statusCode = resPost.statusCode()
                // Após autenticar, o Moodle dispara um 303 (See Other) para /my/
                val isSuccess = if (statusCode in 300..399) {
                    true
                } else {
                    // Se não for redirect, valida se o form de login sumiu
                    val docPost = resPost.parse()
                    docPost.select("form#login").isEmpty()
                }
                if (isSuccess) {
                    // 3. Salvar os cookies finais validados e gravar no CookieManager para o WebView usar
                    val cookiesFinais = resPost.cookies()
                    cookiesFinais.forEach { (key, value) ->
                        cookieManager.setCookie("https://$MOODLE_DOMAIN", "$key=$value; Path=/")
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

    /**
     * ★ NOVO: Obtém o token de Web Service do Moodle usando as credenciais salvas.
     * Usado para acessar a API oficial do Moodle (calendário, cursos, etc).
     */
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