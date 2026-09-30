package com.marinov.openfei.ui.webview

import android.app.Activity
import android.content.Intent
import com.marinov.openfei.data.NetworkChecker
import com.marinov.openfei.data.SessionManager
import com.marinov.openfei.ui.login.LoginActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object WebViewSessionHelper {

    sealed class SessionState {
        object OnlineOk : SessionState()
        object Offline : SessionState()
        object LoginNeeded : SessionState()
    }

    suspend fun checkSession(): SessionState = withContext(Dispatchers.IO) {
        val isOnline = NetworkChecker.isOnline()
        if (!isOnline) {
            return@withContext SessionState.Offline
        }

        when (SessionManager.checkConnectionAndSession()) {
            SessionManager.STATUS_ONLINE_OK -> SessionState.OnlineOk
            SessionManager.STATUS_OFFLINE -> SessionState.Offline
            else -> SessionState.LoginNeeded
        }
    }

    fun openLoginAndFinish(activity: Activity) {
        val intent = Intent(activity, LoginActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        activity.startActivity(intent)
        activity.finish()
    }
}