package com.marinov.colegioetapa

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import org.json.JSONArray
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.stream.Collectors

object UpdateChecker {
    private const val TAG = "UpdateChecker"
    private const val PREFS_NAME = "UpdatePrefs"
    private const val KEY_LAST_VERSION = "last_version"

    interface UpdateListener {
        fun onUpdateAvailable(url: String, version: String, releaseNotes: String)
        fun onUpToDate()
        fun onError(message: String)
    }

    /**
     * @param isManualCheck Se true, ignora a verificação de "versão já notificada" e sempre avisa se houver update.
     */
    fun checkForUpdate(context: Context, isManualCheck: Boolean, listener: UpdateListener) {
        Thread {
            runCatching {
                // A API do GitLab exige que o namespace e o projeto sejam separados por %2F
                val url = URL("https://gitlab.com/api/v4/projects/etapa.app%2Fetapa.app/releases")
                val conn = url.openConnection() as HttpURLConnection
                conn.apply {
                    requestMethod = "GET"
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("User-Agent", "EtapaApp-Android")
                    connectTimeout = 10000
                }

                when (conn.responseCode) {
                    HttpURLConnection.HTTP_OK -> {
                        val json = readResponseStream(conn)
                        // GitLab retorna um Array de releases, a primeira (índice 0) é a mais recente
                        val jsonArray = JSONArray(json)
                        if (jsonArray.length() == 0) {
                            listener.onError("Nenhuma release encontrada no repositório.")
                            return@runCatching
                        }

                        val latestRelease = jsonArray.getJSONObject(0)
                        val latestVersion = latestRelease.getString("tag_name")
                        // Extrai as notas de lançamento (pode ser null ou não existir, então usamos optString)
                        val releaseNotes = latestRelease.optString("description", "Sem notas de lançamento.")
                        val currentVersion = BuildConfig.VERSION_NAME

                        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        val lastNotifiedVersion = prefs.getString(KEY_LAST_VERSION, "") ?: ""

                        if (isVersionGreater(latestVersion, currentVersion)) {
                            // Verifica se deve avisar (sempre avisa se for manual, ou se for uma versão nova não notificada)
                            if (isManualCheck || latestVersion != lastNotifiedVersion) {

                                var apkUrl: String? = null

                                // Pega o link dos assets da release do GitLab
                                if (latestRelease.has("assets")) {
                                    val assets = latestRelease.getJSONObject("assets")
                                    if (assets.has("links")) {
                                        val links = assets.getJSONArray("links")
                                        for (i in 0 until links.length()) {
                                            val link = links.getJSONObject(i)
                                            val linkUrl = link.optString("url", "")
                                            val linkName = link.optString("name", "")
                                            if (linkUrl.endsWith(".apk") || linkName.endsWith(".apk")) {
                                                apkUrl = linkUrl
                                                break
                                            }
                                        }
                                    }
                                }

                                if (apkUrl.isNullOrEmpty()) {
                                    listener.onError("Arquivo APK não foi encontrado nos assets da release da versão $latestVersion.")
                                    return@runCatching
                                }

                                // Salva que já notificamos sobre essa versão (apenas em background)
                                if (!isManualCheck) {
                                    prefs.edit { putString(KEY_LAST_VERSION, latestVersion) }
                                }

                                listener.onUpdateAvailable(apkUrl, latestVersion, releaseNotes)
                            } else {
                                listener.onUpToDate()
                            }
                        } else {
                            listener.onUpToDate()
                        }
                    }
                    else -> listener.onError("HTTP error: ${conn.responseCode}")
                }
            }.onFailure { e ->
                Log.e(TAG, "Erro na verificação", e)
                listener.onError(e.message ?: "Erro desconhecido")
            }
        }.start()
    }

    private fun readResponseStream(conn: HttpURLConnection): String {
        return conn.inputStream.use { inputStream ->
            BufferedReader(InputStreamReader(inputStream)).use { reader ->
                reader.lines().collect(Collectors.joining("\n"))
            }
        }
    }

    private fun normalizeVersion(version: String): String {
        return version.replace(Regex("^[^0-9]+"), "")
    }

    fun isVersionGreater(newVersion: String, currentVersion: String): Boolean {
        val normalizedNew = normalizeVersion(newVersion)
        val normalizedCurrent = normalizeVersion(currentVersion)

        val newParts = normalizedNew.split(".").map { it.toIntOrNull() ?: 0 }
        val currentParts = normalizedCurrent.split(".").map { it.toIntOrNull() ?: 0 }

        for (i in 0 until maxOf(newParts.size, currentParts.size)) {
            val newPart = newParts.getOrElse(i) { 0 }
            val currentPart = currentParts.getOrElse(i) { 0 }

            when {
                newPart > currentPart -> return true
                newPart < currentPart -> return false
            }
        }
        return false
    }
}