package com.marinov.openfei.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

object CacheHelper {
    private const val PREFS_NAME = "DadosFEI"

    private lateinit var appContext: Context
    val gson = Gson()

    val prefs: SharedPreferences by lazy { appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    // ===================== CHAVES =====================
    const val KEY_DISCIPLINAS = "disciplinas_cache"
    const val KEY_PERFIL = "perfil_cache"
    const val KEY_AULAS = "aulas_cache"
    const val KEY_PROVAS_FEI = "provas_fei_cache"
    const val KEY_EVENTOS_MOODLE = "eventos_moodle_cache"
    const val KEY_BOLETOS = "boletos_cache"
    const val KEY_LAST_UPDATE_DISCIPLINAS = "last_update_disciplinas"
    const val KEY_LAST_UPDATE_NOTAS = "last_update_notas"
    const val KEY_LAST_UPDATE_PERFIL = "last_update_perfil"
    const val KEY_LAST_UPDATE_AULAS = "last_update_aulas"
    const val KEY_LAST_UPDATE_CALENDARIO_PROVAS = "last_update_calendario_provas"
    const val KEY_LAST_UPDATE_PROVAS_FEI = "last_update_provas_fei"
    const val KEY_LAST_UPDATE_EVENTOS_MOODLE = "last_update_eventos_moodle"
    const val KEY_LAST_UPDATE_BOLETOS = "last_update_boletos"
    const val KEY_LAST_UPDATE_MEDIAS = "last_update_medias"

    // ===================== DISCIPLINAS =====================
    fun saveDisciplinasCache(disciplinas: List<Disciplina>) {
        prefs.edit {
            putString(KEY_DISCIPLINAS, gson.toJson(disciplinas))
            putLong(KEY_LAST_UPDATE_DISCIPLINAS, System.currentTimeMillis())
        }
    }

    fun getCachedDisciplinas(): List<Disciplina> {
        val json = prefs.getString(KEY_DISCIPLINAS, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<Disciplina>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (_: Exception) { emptyList() }
    }

    // ===================== NOTAS =====================
    fun saveNotasCache(notas: List<Nota>) {
        val file = File(appContext.filesDir, "notas_cache.json")
        file.writeText(gson.toJson(notas))
        prefs.edit { putLong(KEY_LAST_UPDATE_NOTAS, System.currentTimeMillis()) }
    }

    fun getCachedNotas(): List<Nota> {
        val file = File(appContext.filesDir, "notas_cache.json")
        if (!file.exists()) return emptyList()
        return try {
            val type = object : TypeToken<List<Nota>>() {}.type
            gson.fromJson(file.readText(), type) ?: emptyList()
        } catch (_: Exception) { emptyList() }
    }

    // ===================== MÉDIAS =====================
    fun saveMediasCache(medias: Map<String, String>) {
        val file = File(appContext.filesDir, "medias_cache.json")
        file.writeText(gson.toJson(medias))
        prefs.edit { putLong(KEY_LAST_UPDATE_MEDIAS, System.currentTimeMillis()) }
    }

    fun getCachedMedias(): Map<String, String> {
        val file = File(appContext.filesDir, "medias_cache.json")
        if (!file.exists()) return emptyMap()
        return try {
            val type = object : TypeToken<Map<String, String>>() {}.type
            gson.fromJson(file.readText(), type) ?: emptyMap()
        } catch (_: Exception) { emptyMap() }
    }

    // ===================== PERFIL =====================
    fun savePerfilCache(perfil: Perfil) {
        prefs.edit {
            putString(KEY_PERFIL, gson.toJson(perfil))
            putLong(KEY_LAST_UPDATE_PERFIL, System.currentTimeMillis())
        }
    }

    fun getCachedPerfil(): Perfil? {
        val json = prefs.getString(KEY_PERFIL, null) ?: return null
        return try { gson.fromJson(json, Perfil::class.java) } catch (_: Exception) { null }
    }

    // ===================== AULAS =====================
    fun saveAulasCache(aulas: List<Aula>) {
        prefs.edit {
            putString(KEY_AULAS, gson.toJson(aulas))
            putLong(KEY_LAST_UPDATE_AULAS, System.currentTimeMillis())
        }
    }

    fun getCachedAulas(): List<Aula> {
        val json = prefs.getString(KEY_AULAS, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<Aula>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (_: Exception) { emptyList() }
    }

    // ===================== PROVAS FEI =====================
    fun saveProvasFEICache(provas: List<ProvaCalendario>) {
        prefs.edit {
            putString(KEY_PROVAS_FEI, gson.toJson(provas))
            putLong(KEY_LAST_UPDATE_PROVAS_FEI, System.currentTimeMillis())
        }
    }

    fun getCachedProvasFEI(): List<ProvaCalendario> {
        val json = prefs.getString(KEY_PROVAS_FEI, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<ProvaCalendario>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (_: Exception) { emptyList() }
    }

    // ===================== EVENTOS MOODLE =====================
    fun saveEventosMoodleCache(eventos: List<ProvaCalendario>) {
        prefs.edit {
            putString(KEY_EVENTOS_MOODLE, gson.toJson(eventos))
            putLong(KEY_LAST_UPDATE_EVENTOS_MOODLE, System.currentTimeMillis())
        }
    }

    fun getCachedEventosMoodle(): List<ProvaCalendario> {
        val json = prefs.getString(KEY_EVENTOS_MOODLE, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<ProvaCalendario>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (_: Exception) { emptyList() }
    }

    // ===================== BOLETOS =====================
    fun saveBoletosCache(boletos: List<Boleto>) {
        prefs.edit {
            putString(KEY_BOLETOS, gson.toJson(boletos))
            putLong(KEY_LAST_UPDATE_BOLETOS, System.currentTimeMillis())
        }
    }

    fun getCachedBoletos(): List<Boleto> {
        val json = prefs.getString(KEY_BOLETOS, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<Boleto>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (_: Exception) { emptyList() }
    }

    // ===================== LIMPEZA =====================
    fun clearAllCacheFiles() {
        listOf(
            "notas_cache.json", "disciplinas_cache.json", "aulas_cache.json",
            "provas_cache.json", "boletos_cache.json", "perfil_cache.json", "medias_cache.json"
        ).forEach { nome -> File(appContext.filesDir, nome).delete() }

        prefs.edit {
            remove(KEY_LAST_UPDATE_NOTAS); remove(KEY_LAST_UPDATE_DISCIPLINAS)
            remove(KEY_LAST_UPDATE_AULAS); remove(KEY_LAST_UPDATE_CALENDARIO_PROVAS)
            remove(KEY_LAST_UPDATE_PROVAS_FEI); remove(KEY_LAST_UPDATE_EVENTOS_MOODLE)
            remove(KEY_LAST_UPDATE_BOLETOS); remove(KEY_LAST_UPDATE_PERFIL)
            remove(KEY_LAST_UPDATE_MEDIAS)
        }
    }
}