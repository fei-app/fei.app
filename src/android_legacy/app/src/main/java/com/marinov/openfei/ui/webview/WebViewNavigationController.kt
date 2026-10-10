package com.marinov.openfei.ui.webview

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebView
import android.widget.Toast
import androidx.core.net.toUri
import com.marinov.openfei.R
import com.marinov.openfei.data.SessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class WebViewNavigationController(
    private val context: Context,
    private val webView: WebView,
    private val scope: CoroutineScope,
    private val ui: UiCallbacks,
    private val navigation: NavigationCallbacks
) {

    interface UiCallbacks {
        fun showLoading()
        fun hideLoading()
    }

    interface NavigationCallbacks {
        fun shouldInterceptHome(): Boolean
        fun navigateHome()
        fun openLogin()
        fun isAlive(): Boolean
    }

    companion object {
        private const val TAG = "WebViewNavigation"
        private const val HOME_URL_IDENTIFIER =
            "https://interage.fei.org.br/secureserver/portal/graduacao/home"
        private const val MOODLE_HOST = "moodle.fei.edu.br"
        private const val MOODLE_LOGIN_PATH_PREFIX = "/login/"
        private const val MOODLE_HOME_URL = "https://moodle.fei.edu.br/my/"
        private const val MAX_MOODLE_LOGIN_RETRIES = 2
    }

    private var handlingMoodleLoginRedirect = false
    private var moodleLoginRetryCount = 0
    private var isMoodleProtectionOwner = false

    fun handleUrl(url: String?): Boolean {
        if (url == null) return false

        if (isMoodleLoginUrl(url)) {
            handleMoodleLoginRedirect()
            return true
        }

        if (isHomeUrl(url)) {
            if (navigation.shouldInterceptHome()) {
                Handler(Looper.getMainLooper()).post {
                    if (navigation.isAlive()) {
                        navigation.navigateHome()
                    }
                }
                return true
            }
            return false
        }

        val uri = url.toUri()
        val host = uri.host ?: return false

        if (host.endsWith("fei.edu.br") || host.endsWith("fei.org.br")) {
            return false
        }

        return try {
            val intent = Intent(Intent.ACTION_VIEW, uri)
            context.startActivity(intent)
            true
        } catch (_: Exception) {
            Toast.makeText(
                context,
                context.getString(R.string.nenhum_navegador_encontrado),
                Toast.LENGTH_SHORT
            ).show()
            true
        }
    }

    fun onPageStarted(url: String?) {
        if (url == null) return

        if (isMoodleUrl(url)) {
            protegerCookiesMoodleSeNecessario()
            if (!isMoodleLoginUrl(url)) {
                moodleLoginRetryCount = 0
            }
        } else {
            desprotegerCookiesMoodleSeNecessario()
        }
    }

    fun onCleared() {
        desprotegerCookiesMoodleSeNecessario()
    }

    private fun handleMoodleLoginRedirect() {
        if (handlingMoodleLoginRedirect) return

        moodleLoginRetryCount++

        if (moodleLoginRetryCount > MAX_MOODLE_LOGIN_RETRIES) {
            Log.w(
                TAG,
                "Loop de login do Moodle detectado (${moodleLoginRetryCount} tentativas) — chamando LoginActivity"
            )
            moodleLoginRetryCount = 0

            if (!navigation.isAlive()) return

            ui.hideLoading()
            Toast.makeText(
                context,
                context.getString(R.string.sessao_moodle_expirada_login),
                Toast.LENGTH_LONG
            ).show()

            navigation.openLogin()
            return
        }

        handlingMoodleLoginRedirect = true
        ui.showLoading()

        scope.launch {
            try {
                SessionManager.desprotegerCookiesMoodle()
                isMoodleProtectionOwner = false

                val sucesso = SessionManager.forcarRenovacaoCookiesMoodle()
                if (!sucesso) {
                    Log.w(TAG, "Renovação real dos cookies do Moodle falhou")
                    if (navigation.isAlive()) {
                        Toast.makeText(
                            context,
                            context.getString(R.string.erro_servidor_tente_novamente),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Falha ao renovar sessão do Moodle após redirecionamento de /login/", e)
            } finally {
                if (navigation.isAlive()) {
                    protegerCookiesMoodleSeNecessario()
                    WebViewConfig.disableTransitions(webView)
                    webView.loadUrl(MOODLE_HOME_URL)
                }
                handlingMoodleLoginRedirect = false
            }
        }
    }

    private fun isHomeUrl(url: String?): Boolean {
        return url?.contains(HOME_URL_IDENTIFIER) == true
    }

    private fun isMoodleUrl(url: String): Boolean {
        return try {
            url.toUri().host == MOODLE_HOST
        } catch (_: Exception) {
            false
        }
    }

    private fun isMoodleLoginUrl(url: String): Boolean {
        return try {
            val uri = url.toUri()
            uri.host == MOODLE_HOST && (uri.path?.startsWith(MOODLE_LOGIN_PATH_PREFIX) == true)
        } catch (_: Exception) {
            false
        }
    }

    private fun protegerCookiesMoodleSeNecessario() {
        if (!isMoodleProtectionOwner) {
            SessionManager.protegerCookiesMoodle()
            isMoodleProtectionOwner = true
        }
    }

    private fun desprotegerCookiesMoodleSeNecessario() {
        if (isMoodleProtectionOwner) {
            SessionManager.desprotegerCookiesMoodle()
            isMoodleProtectionOwner = false
        }
    }
}