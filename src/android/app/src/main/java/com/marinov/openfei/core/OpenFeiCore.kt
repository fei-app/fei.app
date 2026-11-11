package com.marinov.openfei.core

import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.json.JSONObject
import com.marinov.openfei.login.SessionGuardBridge

object OpenFeiCore {

    private const val TAG = "OpenFeiCore"

    /**
     * ★ BUG 65: System.loadLibrary dentro do init do object gerava
     * ExceptionInInitializerError na 1ª chamada e NoClassDefFoundError em todas
     * as seguintes, sem mensagem útil. Agora falha de forma controlada.
     */
    val isLibraryAvailable: Boolean by lazy {
        try {
            System.loadLibrary("openfei_core_jni")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "Falha ao carregar openfei_core_jni", t)
            false
        }
    }

    private val gson = Gson()

    private external fun nativeIsOnline(): String
    private external fun nativeLogin(user: String, pass: String): String
    private external fun nativeMoodleLogin(user: String, pass: String): String
    private external fun nativeFetchDisciplinas(): String
    private external fun nativeFetchNotas(): String
    private external fun nativeFetchMedias(): String
    private external fun nativeFetchAulas(): String
    private external fun nativeFetchPerfil(): String
    private external fun nativeFetchProvasFei(): String
    private external fun nativeFetchBoletos(): String
    private external fun nativeFetchCarousel(): String
    private external fun nativeDownloadBoleto(tituloId: String, vencimento: String, outDir: String): String
    private external fun nativeFetchEventosMoodle(user: String, pass: String): String
    private external fun nativeClearSession()
    private external fun nativeClearMoodleSession()

    // ---------------------------------------------------------------------
    // Guarda de chamada nativa
    // ---------------------------------------------------------------------

    private inline fun <T> guarded(noinline block: () -> CoreResult<T>): CoreResult<T> {
        if (!isLibraryAvailable) {
            return CoreResult.Error("Biblioteca nativa indisponível", "LIB_UNAVAILABLE")
        }
        return try {
            when (val first = block()) {
                is CoreResult.Error ->
                    if (first.code == "SESSION_EXPIRED") renewAndRetry(block, first) else first
                else -> first
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Falha na chamada nativa", t)
            CoreResult.Error(t.message ?: "Erro nativo", "NATIVE")
        }
    }

    @PublishedApi
    internal fun <T> renewAndRetry(
        block: () -> CoreResult<T>,
        original: CoreResult.Error
    ): CoreResult<T> {
        // O próprio login/logout não pode disparar renovação (recursão).
        if (SessionGuardBridge.isRenewing()) return original

        Log.w(TAG, "SESSION_EXPIRED detectado — renovando sessão e repetindo a chamada")
        val renewed = SessionGuardBridge.renewBlocking()
        if (!renewed) {
            Log.w(TAG, "Renovação não foi possível agora — mantendo erro original")
            return original
        }
        return try {
            block()
        } catch (t: Throwable) {
            Log.e(TAG, "Falha na repetição da chamada nativa", t)
            original
        }
    }

    // ---------------------------------------------------------------------
    // Parse genérico
    // ---------------------------------------------------------------------

    /**
     * ★ BUG 44: usava T::class.java, perdendo os generics.
     * Para fetchMedias (Map<String,String>) o Gson podia produzir Double e
     * estourar ClassCastException na UI. TypeToken com T reificado preserva o tipo.
     */
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

            val type = object : TypeToken<T>() {}.type
            val parsed: T = gson.fromJson(data.toString(), type)
            CoreResult.Success(parsed)
        } catch (e: Exception) {
            CoreResult.Error(e.message ?: "Erro ao parsear JSON", "PARSE")
        }
    }

    /**
     * ★ BUG 43: data == null virava Success(emptyList()), mascarando erro do
     * servidor como "não há dados". Agora é erro explícito.
     */
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
                ?: return CoreResult.Error("Dados ausentes", "PARSE")

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

    fun isOnline(): CoreResult<Boolean> = guarded { parseObject(nativeIsOnline()) }

    fun login(user: String, pass: String): CoreResult<CoreLoginData> =
        guarded { parseObject(nativeLogin(user, pass)) }

    fun moodleLogin(user: String, pass: String): CoreResult<CoreMoodleTokenData> =
        guarded { parseObject(nativeMoodleLogin(user, pass)) }

    fun fetchDisciplinas(): CoreResult<List<CoreDisciplina>> =
        guarded { parseList(nativeFetchDisciplinas()) }

    fun fetchNotas(): CoreResult<List<CoreNota>> =
        guarded { parseList(nativeFetchNotas()) }

    fun fetchMedias(): CoreResult<Map<String, String>> =
        guarded { parseObject(nativeFetchMedias()) }

    fun fetchAulas(): CoreResult<List<CoreAula>> =
        guarded { parseList(nativeFetchAulas()) }

    fun fetchPerfil(): CoreResult<CorePerfil> =
        guarded { parseObject(nativeFetchPerfil()) }

    fun fetchProvasFei(): CoreResult<List<CoreProvaCalendario>> =
        guarded { parseList(nativeFetchProvasFei()) }

    fun fetchBoletos(): CoreResult<List<CoreBoleto>> =
        guarded { parseList(nativeFetchBoletos()) }

    fun fetchCarousel(): CoreResult<List<CoreCarouselItem>> =
        guarded { parseList(nativeFetchCarousel()) }

    fun downloadBoleto(tituloId: String, vencimento: String, outDir: String): CoreResult<CoreDownloadBoletoResult> =
        guarded { parseObject(nativeDownloadBoleto(tituloId, vencimento, outDir)) }

    fun fetchEventosMoodle(user: String, pass: String): CoreResult<List<CoreProvaCalendario>> =
        guarded { parseList(nativeFetchEventosMoodle(user, pass)) }

    /** Limpa FEI + Moodle. */
    fun clearSession() {
        if (!isLibraryAvailable) return
        runCatching { nativeClearSession() }
    }

    /** ★ BUG 18: limpa só o Moodle (renovação de cookies do WebView). */
    fun clearMoodleSession() {
        if (!isLibraryAvailable) return
        runCatching { nativeClearMoodleSession() }
    }
}