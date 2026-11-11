package com.marinov.openfei.network

import android.content.Context
import android.net.ConnectivityManager
import android.util.Log
import com.marinov.openfei.core.CoreResult
import com.marinov.openfei.core.OpenFeiCore

object NetworkChecker {

    private const val TAG = "NetworkChecker"

    fun isOnline(): Boolean {
        return when (val result = OpenFeiCore.isOnline()) {
            is CoreResult.Success -> {
                Log.d(TAG, "Rust isOnline=${result.data}")
                result.data
            }
            is CoreResult.Error -> {
                Log.e(TAG, "Erro Rust no isOnline: ${result.code} - ${result.message}")
                false
            }
        }
    }

    /**
     * Retorna true quando a rede ativa é considerada limitada (ex.: dados móveis),
     * segundo o próprio sistema (uso de dados). Retorna false para redes ilimitadas
     * (ex.: Wi-Fi) ou quando não é possível determinar.
     */
    fun isNetworkMetered(context: Context): Boolean {
        return try {
            val cm =
                context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.isActiveNetworkMetered
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao verificar se a rede é limitada", e)
            false
        }
    }
}