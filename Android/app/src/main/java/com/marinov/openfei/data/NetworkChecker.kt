package com.marinov.openfei.data

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * Verificador de conectividade usando NCSI (Network Connectivity Status Indicator) da Microsoft.
 * Usa apenas HTTP conforme especificação oficial da Microsoft para verificação de conectividade.
 */
object NetworkChecker {
    private const val TAG = "NetworkChecker"

    // Endpoints oficiais da Microsoft para verificação de conectividade (HTTP apenas)
    private val NCSI_ENDPOINTS = listOf(
        EndpointInfo("http://www.msftconnecttest.com/connecttest.txt", "Microsoft Connect Test"),
        EndpointInfo("http://www.msftncsi.com/ncsi.txt", "Microsoft NCSI")
    )

    private const val TIMEOUT_MS = 8000
    private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 16; sdk_gphone64_x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Mobile Safari/537.36"

    private data class EndpointInfo(
        val url: String,
        val expectedResponse: String
    )

    /**
     * Verifica se há conexão com a internet usando endpoints HTTP da Microsoft.
     * Retorna true apenas se conseguir fazer requisição HTTP bem-sucedida e o conteúdo corresponder ao esperado.
     */
    suspend fun isOnline(): Boolean = withContext(Dispatchers.IO) {
        for ((index, endpoint) in NCSI_ENDPOINTS.withIndex()) {
            try {
                if (checkEndpoint(endpoint)) {
                    Log.d(TAG, "NCSI confirmou conexão online (endpoint ${index + 1}: ${endpoint.url})")
                    return@withContext true
                }
            } catch (e: Exception) {
                Log.d(TAG, "Falha no endpoint ${index + 1} (${endpoint.url}): ${e.javaClass.simpleName} - ${e.message}")
            }
        }

        Log.d(TAG, "NCSI confirmou que está offline (todos os endpoints falharam)")
        false
    }

    private fun checkEndpoint(endpoint: EndpointInfo): Boolean {
        var connection: HttpURLConnection? = null
        return try {
            val url = URL(endpoint.url)
            connection = url.openConnection() as HttpURLConnection
            connection.apply {
                requestMethod = "GET"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                useCaches = false
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", "*/*")
                setRequestProperty("Connection", "close")
            }

            val responseCode = connection.responseCode

            if (responseCode == HttpURLConnection.HTTP_OK) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }.trim()
                response == endpoint.expectedResponse
            } else {
                Log.d(TAG, "Resposta HTTP não OK ($responseCode) para ${endpoint.url}")
                false
            }
        } catch (e: Exception) {
            Log.d(TAG, "Exceção ao conectar em ${endpoint.url}: ${e.javaClass.simpleName} - ${e.message}")
            throw e
        } finally {
            connection?.disconnect()
        }
    }
}