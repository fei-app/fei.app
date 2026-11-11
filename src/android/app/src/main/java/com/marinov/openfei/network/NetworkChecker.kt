package com.marinov.openfei.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.marinov.openfei.core.CoreResult
import com.marinov.openfei.core.OpenFeiCore

object NetworkChecker {

    private const val TAG = "NetworkChecker"

    private lateinit var appContext: Context

    fun init(context: Context) {
        appContext = context.applicationContext
    }
    fun isOnline(): Boolean {
        return when (val result = OpenFeiCore.isOnline()) {
            is CoreResult.Success -> result.data
            is CoreResult.Error -> {
                Log.w(TAG, "Sonda Rust falhou (${result.code}); usando conectividade do sistema")
                hasValidatedNetwork()
            }
        }
    }

    private fun hasValidatedNetwork(): Boolean {
        return ::appContext.isInitialized && try {
            val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao verificar conectividade", e)
            false
        }
    }

    fun isNetworkMetered(context: Context): Boolean = try {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        cm.isActiveNetworkMetered
    } catch (e: Exception) {
        Log.e(TAG, "Erro ao verificar rede limitada", e)
        false
    }
}