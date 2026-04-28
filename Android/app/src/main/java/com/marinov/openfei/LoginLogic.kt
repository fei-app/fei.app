package com.marinov.openfei

import android.content.Context
import android.webkit.CookieManager
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Connection
import org.jsoup.Jsoup

data class LoginResult(
    val success: Boolean,
    val errorMessage: String = ""
)

object LoginLogic {
    const val LOGIN_URL = "https://interage.fei.org.br/secureserver/portal"
    suspend fun performLogin(user: String, pass: String, context: Context): LoginResult = withContext(Dispatchers.IO) {
        try {
            // 1. Acessar a página de login para pegar os cookies iniciais e o Token
            val resGet = Jsoup.connect(LOGIN_URL)
                .method(Connection.Method.GET)
                .execute()

            val docGet = resGet.parse()
            val token = docGet.select("input[name=__RequestVerificationToken]").`val`()
            val cookies = resGet.cookies()

            if (token.isEmpty()) {
                return@withContext LoginResult(false, "Não foi possível obter o token de segurança da página.")
            }

            // 2. Fazer o POST com os dados de login
            val resPost = Jsoup.connect("$LOGIN_URL/")
                .data("__RequestVerificationToken", token)
                .data("Usuario", user)
                .data("Senha", pass)
                .cookies(cookies)
                .method(Connection.Method.POST)
                .followRedirects(true)
                .execute()

            val docPost = resPost.parse()
            val isSuccess = docPost.select("#btn-login").isEmpty() // Se o botão sumiu, logou com sucesso

            if (isSuccess) {
                // 3. Salvar os cookies de sessão no CookieManager com expiração de 15 minutos
                val cookieManager = CookieManager.getInstance()
                cookieManager.setAcceptCookie(true)
                resPost.cookies().forEach { (key, value) ->
                    cookieManager.setCookie(
                        "https://interage.fei.org.br",
                        "$key=$value; Domain=interage.fei.org.br; Path=/; Max-Age=900"
                    )
                }
                cookieManager.flush()

                // 4. Atualizar estado de login nas SharedPreferences
                context.getSharedPreferences(LoginActivity.PREFS_LOGIN, Context.MODE_PRIVATE).edit {
                    putBoolean(LoginActivity.KEY_IS_LOGGED_IN, true)
                    putString(LoginActivity.KEY_USER, user)
                    putString(LoginActivity.KEY_PASS, pass)
                }

                return@withContext LoginResult(true)
            } else {
                // Falha no login → garantir que estado fique como false
                context.getSharedPreferences(LoginActivity.PREFS_LOGIN, Context.MODE_PRIVATE).edit {
                    putBoolean(LoginActivity.KEY_IS_LOGGED_IN, false)
                }
                val errorMsg = docPost.select(".field-validation-error").text()
                return@withContext LoginResult(false, errorMsg.ifEmpty { "Usuário ou senha incorretos." })
            }
        } catch (e: Exception) {
            // Em caso de erro, também marcamos como não logado
            context.getSharedPreferences(LoginActivity.PREFS_LOGIN, Context.MODE_PRIVATE).edit {
                putBoolean(LoginActivity.KEY_IS_LOGGED_IN, false)
            }
            return@withContext LoginResult(false, "Erro de conexão: ${e.message}")
        }
    }
}