package com.marinov.openfei.data

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
}