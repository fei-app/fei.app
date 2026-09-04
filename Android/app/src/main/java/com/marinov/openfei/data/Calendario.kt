package com.marinov.openfei.data

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object CalendarioRepository {

    private const val TAG = "CalendarioRepository"

    private const val URL_CALENDARIO_PROVAS =
        "https://interage.fei.org.br/secureserver/portal/graduacao/sala-dos-professores/informacoes-academicas/provas"

    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 16; sdk_gphone64_x86_64 Build/BE2A.250530.026.D1; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/133.0.6943.137 Mobile Safari/537.36"

    private val gson = Gson()

    private lateinit var appContext: Context

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    // ===================== PROVAS FEI =====================

    suspend fun obterProvasFEI(online: Boolean): List<ProvaCalendario> {
        Log.d(TAG, "obterProvasFEI | online=$online")

        return if (online) {
            try {
                val provas = fetchCalendarioProvasFromPortal()
                Log.d(TAG, "obterProvasFEI online sucesso | size=${provas.size}")

                CacheHelper.saveProvasFEICache(provas)
                provas
            } catch (e: SessionExpiredException) {
                Log.w(TAG, "obterProvasFEI online: sessão expirada", e)
                throw e
            } catch (e: Exception) {
                if (e !is CancellationException) {
                    Log.e(TAG, "Erro ao buscar provas FEI online", e)
                }

                val cache = CacheHelper.getCachedProvasFEI()
                Log.d(TAG, "obterProvasFEI fallback cache | size=${cache.size}")
                cache
            }
        } else {
            val cache = CacheHelper.getCachedProvasFEI()
            Log.d(TAG, "obterProvasFEI offline cache | size=${cache.size}")
            cache
        }
    }

    /**
     * Busca online de forma explícita e retorna null se falhar.
     * Isso permite que o caller saiba se a sincronização foi realmente online.
     */
    suspend fun obterProvasFEIOnlineOrNull(): List<ProvaCalendario>? {
        return try {
            val provas = fetchCalendarioProvasFromPortal()
            Log.d(TAG, "obterProvasFEIOnlineOrNull sucesso | size=${provas.size}")

            CacheHelper.saveProvasFEICache(provas)
            provas
        } catch (e: SessionExpiredException) {
            Log.w(TAG, "obterProvasFEIOnlineOrNull: sessão expirada", e)
            null
        } catch (e: Exception) {
            if (e !is CancellationException) {
                Log.e(TAG, "obterProvasFEIOnlineOrNull: erro", e)
            }
            null
        }
    }

    // Mantida para compatibilidade (usada pela Home)
    suspend fun obterCalendarioProvas(online: Boolean): List<ProvaCalendario> {
        return obterProvasFEI(online)
    }

    fun obterCalendarioProvasCache(): List<ProvaCalendario> =
        CacheHelper.getCachedProvasFEI()

    fun obterProvasFEICache(): List<ProvaCalendario> =
        CacheHelper.getCachedProvasFEI()

    // ===================== EVENTOS MOODLE =====================

    suspend fun obterEventosMoodle(online: Boolean): List<ProvaCalendario> {
        Log.d(TAG, "obterEventosMoodle | online=$online")

        return if (online) {
            try {
                val eventos = fetchMoodleCalendarEvents()
                Log.d(TAG, "obterEventosMoodle online sucesso | size=${eventos.size}")

                CacheHelper.saveEventosMoodleCache(eventos)
                eventos
            } catch (e: SessionExpiredException) {
                Log.w(TAG, "obterEventosMoodle online: sessão expirada", e)
                throw e
            } catch (e: Exception) {
                if (e !is CancellationException) {
                    Log.e(TAG, "Erro ao buscar eventos Moodle online", e)
                }

                val cache = CacheHelper.getCachedEventosMoodle()
                Log.d(TAG, "obterEventosMoodle fallback cache | size=${cache.size}")
                cache
            }
        } else {
            val cache = CacheHelper.getCachedEventosMoodle()
            Log.d(TAG, "obterEventosMoodle offline cache | size=${cache.size}")
            cache
        }
    }

    /**
     * Busca online de forma explícita e retorna null se falhar.
     * Isso permite que o caller saiba se a sincronização foi realmente online.
     */
    suspend fun obterEventosMoodleOnlineOrNull(): List<ProvaCalendario>? {
        return try {
            val eventos = fetchMoodleCalendarEvents()
            Log.d(TAG, "obterEventosMoodleOnlineOrNull sucesso | size=${eventos.size}")

            CacheHelper.saveEventosMoodleCache(eventos)
            eventos
        } catch (e: SessionExpiredException) {
            Log.w(TAG, "obterEventosMoodleOnlineOrNull: sessão expirada", e)
            null
        } catch (e: Exception) {
            if (e !is CancellationException) {
                Log.e(TAG, "obterEventosMoodleOnlineOrNull: erro", e)
            }
            null
        }
    }

    fun obterEventosMoodleCache(): List<ProvaCalendario> =
        CacheHelper.getCachedEventosMoodle()

    // ===================== FETCH PROVAS FEI =====================

    private suspend fun fetchCalendarioProvasFromPortal(): List<ProvaCalendario> {
        Log.d(TAG, "fetchCalendarioProvasFromPortal iniciado")

        val disciplinas = DisciplinasRepository.obterDisciplinas(online = true)
        val mapaNomes = disciplinas.associate { it.codigo to it.nome }

        Log.d(TAG, "fetchCalendarioProvasFromPortal disciplinas size=${disciplinas.size}")

        val doc = SessionManager.fetchPage(URL_CALENDARIO_PROVAS)

        val accordion = doc.selectFirst("#accordion-provas")
            ?: throw SessionExpiredException("Accordion de provas não encontrado")

        val panels = accordion.select("div.panel.panel-default")
        Log.d(TAG, "fetchCalendarioProvasFromPortal panels size=${panels.size}")

        val provas = mutableListOf<ProvaCalendario>()

        for (panel in panels) {
            val tituloLink = panel.selectFirst(".panel-title a")
            val titulo = tituloLink?.text()?.trim() ?: continue

            val tipoProva = when {
                titulo.contains("(P1)") -> "P1"
                titulo.contains("(P2)") -> "P2"
                titulo.contains("(P3)") -> "P3"
                else -> continue
            }

            val tabela = panel.selectFirst("div.panel-body table.table") ?: continue
            val linhas = tabela.select("tbody > tr")

            for (linha in linhas) {
                val disciplinaElem = linha.selectFirst("td[class*=\"Disciplina\"]")
                val provaElem = linha.selectFirst("td[class*=\"Prova\"]")
                val horaElem = linha.selectFirst("td[class*=\"Hora\"]")
                val salaElem = linha.selectFirst("td[class*=\"Sala\"]")
                val coordenadorElem = linha.selectFirst("td[class*=\"Coordenador\"]")

                if (disciplinaElem == null || provaElem == null || horaElem == null ||
                    salaElem == null || coordenadorElem == null
                ) continue

                val codigo = disciplinaElem.text().trim()
                val provaTexto = provaElem.text().trim()
                val hora = horaElem.text().trim()
                val sala = salaElem.text().trim()
                val coordenador = coordenadorElem.text().trim()

                val dataProva = provaTexto.split(" ").firstOrNull() ?: provaTexto

                if (codigo.isNotEmpty() && dataProva.isNotEmpty()) {
                    val nome = mapaNomes[codigo] ?: codigo
                    provas.add(
                        ProvaCalendario(
                            codigo,
                            nome,
                            dataProva,
                            hora,
                            sala,
                            coordenador,
                            tipoProva
                        )
                    )
                }
            }
        }

        Log.d(TAG, "fetchCalendarioProvasFromPortal final | provas=${provas.size}")
        return provas
    }

    // ===================== FETCH MOODLE =====================

    private suspend fun fetchMoodleCalendarEvents(): List<ProvaCalendario> {
        Log.d(TAG, "fetchMoodleCalendarEvents iniciado")

        val token = LoginLogic.getMoodleToken(appContext)
        if (token == null) {
            Log.w(TAG, "Token do Moodle indisponível — lançando SessionExpiredException para preservar cache")
            throw SessionExpiredException("Token do Moodle indisponível")
        }

        val siteInfoJson = fetchMoodleApi(token, "core_webservice_get_site_info")

        val userId = try {
            val element = JsonParser.parseString(siteInfoJson)
            if (element.isJsonObject) element.asJsonObject.get("userid")?.asInt else null
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao parsear siteInfo", e)
            null
        }

        if (userId == null) {
            Log.w(TAG, "Não foi possível obter userId do Moodle")
            throw SessionExpiredException("userId do Moodle indisponível")
        }

        Log.d(TAG, "fetchMoodleCalendarEvents userId=$userId")

        val coursesJson = fetchMoodleApi(
            token,
            "core_enrol_get_users_courses",
            mapOf("userid" to userId.toString())
        )

        val courses: List<MoodleCourse> = try {
            val type = object : TypeToken<List<MoodleCourse>>() {}.type
            gson.fromJson(coursesJson, type) ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao parsear cursos do Moodle", e)
            emptyList()
        }

        Log.d(TAG, "fetchMoodleCalendarEvents courses size=${courses.size}")

        if (courses.isEmpty()) {
            Log.w(TAG, "Nenhum curso encontrado no Moodle")
            return emptyList()
        }

        val courseIds = courses.map { it.id }
        val eventsParams = mutableMapOf<String, String>()

        courseIds.forEachIndexed { index, id ->
            eventsParams["events[courseids][$index]"] = id.toString()
        }

        eventsParams["options[userevents]"] = "1"
        eventsParams["options[siteevents]"] = "1"
        eventsParams["options[timestart]"] = "0"

        val eventsJson = fetchMoodleApi(token, "core_calendar_get_calendar_events", eventsParams)

        val events: List<MoodleEvent> = try {
            val element = JsonParser.parseString(eventsJson)

            if (element.isJsonObject) {
                val arr = element.asJsonObject.getAsJsonArray("events")
                    ?: throw SessionExpiredException("Array de eventos Moodle ausente")

                val type = object : TypeToken<List<MoodleEvent>>() {}.type
                gson.fromJson<List<MoodleEvent>>(arr, type) ?: emptyList()
            } else {
                throw SessionExpiredException("Resposta de eventos Moodle não é um objeto JSON")
            }
        } catch (e: SessionExpiredException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao parsear eventos do Moodle", e)
            throw SessionExpiredException("Falha ao parsear eventos Moodle")
        }

        Log.d(TAG, "fetchMoodleCalendarEvents events size=${events.size}")

        val mapped = mapMoodleEventsToProvas(events, courses)
        Log.d(TAG, "fetchMoodleCalendarEvents mapped size=${mapped.size}")

        return mapped
    }

    private suspend fun fetchMoodleApi(
        token: String,
        function: String,
        extraParams: Map<String, String> = emptyMap()
    ): String = withContext(Dispatchers.IO) {

        val url = "https://moodle.fei.edu.br/webservice/rest/server.php"

        Log.d(TAG, "fetchMoodleApi function=$function")

        val conn = Jsoup.connect(url)
            .data("wstoken", token)
            .data("wsfunction", function)
            .data("moodlewsrestformat", "json")
            .ignoreContentType(true)
            .userAgent(USER_AGENT)

        extraParams.forEach { (key, value) -> conn.data(key, value) }

        val response = conn.execute()
        val body = response.body()

        Log.d(
            TAG,
            "fetchMoodleApi function=$function | status=${response.statusCode()} | bodyLength=${body.length}"
        )

        try {
            val jsonElement = JsonParser.parseString(body)

            if (jsonElement.isJsonObject) {
                val jsonObj = jsonElement.asJsonObject

                if (jsonObj.has("error") && !jsonObj.get("error").isJsonNull) {
                    val errorCode = try {
                        jsonObj.get("errorcode")?.asString ?: ""
                    } catch (_: Exception) {
                        ""
                    }

                    val errorMsg = jsonObj.get("error")?.asString ?: "erro desconhecido"

                    Log.e(TAG, "Erro na API do Moodle ($function): [$errorCode] $errorMsg")

                    if (errorCode == "invalidtoken" || errorCode == "accessexception") {
                        throw SessionExpiredException("Token Moodle inválido ou acesso negado: $errorMsg")
                    }
                }
            }
        } catch (e: Exception) {
            if (e is SessionExpiredException) throw e
            Log.w(TAG, "Resposta da API do Moodle ($function) não pôde ser parseada como JSON", e)
        }

        body
    }

    private fun formatMoodleTimestamp(timestamp: Long): Pair<String, String> {
        val instant = Instant.ofEpochSecond(timestamp)
        val zoneId = ZoneId.of("America/Sao_Paulo")

        var zonedDateTime = instant.atZone(zoneId)

        if (zonedDateTime.hour == 0 && zonedDateTime.minute == 0 && zonedDateTime.second == 0) {
            zonedDateTime = zonedDateTime.minusDays(1).withHour(23).withMinute(59).withSecond(0)
        }

        val formatterDate = DateTimeFormatter.ofPattern("dd/MM")
        val formatterTime = DateTimeFormatter.ofPattern("HH:mm")

        return Pair(zonedDateTime.format(formatterDate), zonedDateTime.format(formatterTime))
    }

    private fun mapMoodleEventsToProvas(
        events: List<MoodleEvent>,
        courses: List<MoodleCourse>
    ): List<ProvaCalendario> {
        val mapa = courses.associateBy { it.id }

        return events.mapNotNull { event ->
            val course = event.courseid?.let { mapa[it] }

            val codigo = course?.shortname ?: "Moodle"
            val sala = course?.fullname
            val nomeEvento = event.name
            val nomeLimpo = nomeEvento
                .replace("está marcado(a) para esta data", "")
                .trim()

            val (data, hora) = formatMoodleTimestamp(event.timestart)

            ProvaCalendario(
                codigo,
                nomeLimpo,
                data,
                hora,
                sala,
                "",
                "Moodle"
            )
        }
    }
}