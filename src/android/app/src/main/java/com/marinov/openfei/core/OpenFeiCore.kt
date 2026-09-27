package com.marinov.openfei.core

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.json.JSONObject

object OpenFeiCore {

    init {
        System.loadLibrary("openfei_core_jni")
    }

    private val gson = Gson()

    private external fun nativeIsOnline(): String
    private external fun nativeLogin(user: String, pass: String): String
    private external fun nativeMoodleLogin(user: String, pass: String): String
    private external fun nativeMoodleToken(user: String, pass: String): String

    private external fun nativeFetchDisciplinas(): String
    private external fun nativeFetchNotas(): String
    private external fun nativeFetchMedias(): String
    private external fun nativeFetchAulas(): String
    private external fun nativeFetchPerfil(): String
    private external fun nativeFetchProvasFei(): String
    private external fun nativeFetchBoletos(): String
    private external fun nativeFetchCarousel(): String

    private external fun nativeDownloadBoleto(
        tituloId: String,
        vencimento: String,
        outDir: String
    ): String

    private external fun nativeFetchEventosMoodle(user: String, pass: String): String
    private external fun nativeFetchEventosMoodleWithToken(token: String): String

    private external fun nativeClearSession()

    // ---------------------------------------------------------------------
    // Parse genérico
    // ---------------------------------------------------------------------

    private inline fun <reified T> parseObject(json: String): CoreResult<T> {
        return try {
            val obj = JSONObject(json)

            if (!obj.optBoolean("ok", false)) {
                return CoreResult.Error(
                    message = obj.optString("error", "Erro desconhecido"),
                    code = obj.optString("errorCode", "UNKNOWN")
                )
            }

            val data = obj.opt("data")
                ?: return CoreResult.Error("Dados ausentes", "PARSE")

            val parsed = gson.fromJson(data.toString(), T::class.java)
            CoreResult.Success(parsed)
        } catch (e: Exception) {
            CoreResult.Error(e.message ?: "Erro ao parsear JSON", "PARSE")
        }
    }

    private inline fun <reified T> parseList(json: String): CoreResult<List<T>> {
        return try {
            val obj = JSONObject(json)

            if (!obj.optBoolean("ok", false)) {
                return CoreResult.Error(
                    message = obj.optString("error", "Erro desconhecido"),
                    code = obj.optString("errorCode", "UNKNOWN")
                )
            }

            val data = obj.optJSONArray("data")
            if (data == null) {
                return CoreResult.Success(emptyList())
            }

            val type = object : TypeToken<List<T>>() {}.type
            val parsed: List<T> = gson.fromJson(data.toString(), type)

            CoreResult.Success(parsed)
        } catch (e: Exception) {
            CoreResult.Error(e.message ?: "Erro ao parsear lista", "PARSE")
        }
    }

    // ---------------------------------------------------------------------
    // API pública Kotlin
    // ---------------------------------------------------------------------

    fun isOnline(): CoreResult<Boolean> = parseObject(nativeIsOnline())

    fun login(user: String, pass: String): CoreResult<CoreLoginData> =
        parseObject(nativeLogin(user, pass))

    fun moodleLogin(user: String, pass: String): CoreResult<CoreMoodleTokenData> =
        parseObject(nativeMoodleLogin(user, pass))

    fun moodleToken(user: String, pass: String): CoreResult<CoreMoodleTokenData> =
        parseObject(nativeMoodleToken(user, pass))

    fun fetchDisciplinas(): CoreResult<List<CoreDisciplina>> =
        parseList(nativeFetchDisciplinas())

    fun fetchNotas(): CoreResult<List<CoreNota>> =
        parseList(nativeFetchNotas())

    fun fetchMedias(): CoreResult<Map<String, String>> =
        parseObject(nativeFetchMedias())

    fun fetchAulas(): CoreResult<List<CoreAula>> =
        parseList(nativeFetchAulas())

    fun fetchPerfil(): CoreResult<CorePerfil> =
        parseObject(nativeFetchPerfil())

    fun fetchProvasFei(): CoreResult<List<CoreProvaCalendario>> =
        parseList(nativeFetchProvasFei())

    fun fetchBoletos(): CoreResult<List<CoreBoleto>> =
        parseList(nativeFetchBoletos())

    fun fetchCarousel(): CoreResult<List<CoreCarouselItem>> =
        parseList(nativeFetchCarousel())

    fun downloadBoleto(
        tituloId: String,
        vencimento: String,
        outDir: String
    ): CoreResult<CoreDownloadBoletoResult> =
        parseObject(nativeDownloadBoleto(tituloId, vencimento, outDir))

    fun fetchEventosMoodle(user: String, pass: String): CoreResult<List<CoreProvaCalendario>> =
        parseList(nativeFetchEventosMoodle(user, pass))

    fun fetchEventosMoodleWithToken(token: String): CoreResult<List<CoreProvaCalendario>> =
        parseList(nativeFetchEventosMoodleWithToken(token))

    fun clearSession() {
        nativeClearSession()
    }
}