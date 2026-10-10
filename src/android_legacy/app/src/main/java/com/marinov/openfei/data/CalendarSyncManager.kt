package com.marinov.openfei.data

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentProviderOperation
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.provider.CalendarContract
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.TimeZone
import java.util.UUID
import androidx.core.content.edit

@SuppressLint("MissingPermission")
object CalendarSyncManager {

    private const val TAG = "CalendarSyncManager"

    private const val PREFS_NAME = "CalendarSyncPrefs"
    private const val KEY_ENABLED = "exibir_eventos_agenda_sistema"

    // Conta interna do app
    private const val ACCOUNT_NAME = "com.marinov.openfei.calendar"
    private const val ACCOUNT_TYPE = CalendarContract.ACCOUNT_TYPE_LOCAL
    private const val CALENDAR_NAME = "OpenFEI - Provas e Tarefas"

    private const val EVENT_URI_PREFIX = "openfei://event/"

    private const val CALENDAR_COLOR = 0xFF3F51B5.toInt()

    // 6 horas antes, 1 dia antes, 2 dias antes, 3 dias antes
    private val REMINDER_MINUTES = listOf(360, 1440, 2880, 4320)

    private data class CalendarEventItem(
        val uid: String,
        val prova: ProvaCalendario,
        val start: ZonedDateTime,
        val end: ZonedDateTime,
        val title: String,
        val description: String,
        val location: String
    )

    private data class ExistingEvent(
        val id: Long,
        val uid: String?,
        val title: String,
        val description: String,
        val location: String,
        val dtStart: Long,
        val dtEnd: Long
    )

    private data class FetchResult(
        val eventos: List<ProvaCalendario>,
        val allowDeleteMissing: Boolean
    )

    fun isEnabled(context: Context): Boolean {
        return context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false)
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit {
                putBoolean(KEY_ENABLED, enabled)
            }

        Log.d(TAG, "Recurso de agenda do sistema enabled=$enabled")
    }

    fun hasCalendarPermission(context: Context): Boolean {
        val read = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CALENDAR
        ) == PackageManager.PERMISSION_GRANTED

        val write = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.WRITE_CALENDAR
        ) == PackageManager.PERMISSION_GRANTED

        return read && write
    }

    /**
     * Usado principalmente pela SettingsActivity.
     * Se fetchOnline = true, tenta buscar online e só permite remoção de eventos ausentes
     * se ambas as fontes tiverem sido atualizadas com sucesso.
     */
    suspend fun syncIfNeeded(
        context: Context,
        fetchOnline: Boolean = false,
        force: Boolean = false
    ) {
        val enabled = isEnabled(context)
        val hasPermission = hasCalendarPermission(context)

        Log.d(
            TAG,
            "syncIfNeeded inicio | enabled=$enabled | permission=$hasPermission | fetchOnline=$fetchOnline | force=$force"
        )

        if (!enabled) {
            Log.d(TAG, "syncIfNeeded abortado: recurso desativado")
            return
        }

        if (!hasPermission) {
            Log.w(TAG, "syncIfNeeded abortado: permissão de calendário não concedida")
            return
        }

        withContext(Dispatchers.IO) {
            try {
                CacheHelper.init(context)
                CalendarioRepository.init(context)

                val result = if (fetchOnline) {
                    fetchOnlineWithFallback()
                } else {
                    FetchResult(getCachedEvents(), false)
                }

                Log.d(
                    TAG,
                    "syncIfNeeded eventos obtidos=${result.eventos.size} | allowDeleteMissing=${result.allowDeleteMissing}"
                )

                syncEventsIncremental(
                    context = context,
                    eventos = result.eventos,
                    ensureAllReminders = force,
                    allowDeleteMissing = result.allowDeleteMissing
                )
            } catch (e: Exception) {
                Log.e(TAG, "Erro ao sincronizar eventos com a agenda do sistema", e)
            }
        }
    }

    /**
     * Usado quando o caller já atualizou o cache manualmente
     * (ex.: BackgroundService e CalendarioProvas).
     */
    suspend fun syncCached(
        context: Context,
        force: Boolean = false,
        allowDeleteMissing: Boolean = false
    ) {
        val enabled = isEnabled(context)
        val hasPermission = hasCalendarPermission(context)

        Log.d(
            TAG,
            "syncCached inicio | enabled=$enabled | permission=$hasPermission | force=$force | allowDeleteMissing=$allowDeleteMissing"
        )

        if (!enabled) {
            Log.d(TAG, "syncCached abortado: recurso desativado")
            return
        }

        if (!hasPermission) {
            Log.w(TAG, "syncCached abortado: permissão de calendário não concedida")
            return
        }

        withContext(Dispatchers.IO) {
            try {
                CacheHelper.init(context)

                val eventos = getCachedEvents()

                Log.d(
                    TAG,
                    "syncCached eventos do cache=${eventos.size}"
                )

                syncEventsIncremental(
                    context = context,
                    eventos = eventos,
                    ensureAllReminders = force,
                    allowDeleteMissing = allowDeleteMissing
                )
            } catch (e: Exception) {
                Log.e(TAG, "Erro ao sincronizar eventos do cache com a agenda do sistema", e)
            }
        }
    }

    suspend fun removeAll(context: Context) {
        Log.d(TAG, "removeAll iniciado")

        withContext(Dispatchers.IO) {
            try {
                if (!hasCalendarPermission(context)) {
                    Log.w(TAG, "removeAll abortado: sem permissão de calendário")
                    return@withContext
                }

                deleteAllAppEvents(context)

                Log.d(TAG, "Eventos do calendário OpenFEI removidos da agenda do sistema")
            } catch (e: Exception) {
                Log.e(TAG, "Erro ao remover eventos da agenda do sistema", e)
            }
        }
    }

    private fun getCachedEvents(): List<ProvaCalendario> {
        val provasFei = CacheHelper.getCachedProvasFEI()
        val eventosMoodle = CacheHelper.getCachedEventosMoodle()

        Log.d(
            TAG,
            "getCachedEvents | FEI=${provasFei.size} | Moodle=${eventosMoodle.size}"
        )

        return provasFei + eventosMoodle
    }

    private suspend fun fetchOnlineWithFallback(): FetchResult {
        Log.d(TAG, "fetchOnlineWithFallback iniciado")

        var feiOk = false
        var moodleOk = false

        val provasFeiOnline = CalendarioRepository.obterProvasFEIOnlineOrNull()
        if (provasFeiOnline != null) {
            feiOk = true
        }

        val eventosMoodleOnline = CalendarioRepository.obterEventosMoodleOnlineOrNull()
        if (eventosMoodleOnline != null) {
            moodleOk = true
        }

        val provasFei = provasFeiOnline ?: CacheHelper.getCachedProvasFEI()
        val eventosMoodle = eventosMoodleOnline ?: CacheHelper.getCachedEventosMoodle()

        Log.d(
            TAG,
            "fetchOnlineWithFallback | feiOk=$feiOk | moodleOk=$moodleOk | FEI=${provasFei.size} | Moodle=${eventosMoodle.size}"
        )

        return FetchResult(
            eventos = provasFei + eventosMoodle,
            allowDeleteMissing = feiOk && moodleOk
        )
    }

    private fun syncEventsIncremental(
        context: Context,
        eventos: List<ProvaCalendario>,
        ensureAllReminders: Boolean,
        allowDeleteMissing: Boolean
    ) {
        Log.d(
            TAG,
            "syncEventsIncremental iniciado | eventos=${eventos.size} | ensureAllReminders=$ensureAllReminders | allowDeleteMissing=$allowDeleteMissing"
        )

        val calendarId = ensureCalendar(context)
        if (calendarId == null) {
            Log.e(TAG, "syncEventsIncremental abortado: não foi possível obter/criar o calendário interno")
            return
        }

        val currentYear = ZonedDateTime.now(ZoneId.systemDefault()).year
        val currentItems = buildEventItems(eventos, currentYear)

        Log.d(TAG, "syncEventsIncremental currentItems=${currentItems.size} | currentYear=$currentYear")

        val existingEvents = queryExistingEvents(context, calendarId)
        val existingByUid = HashMap<String, ExistingEvent>()
        val invalidOrDuplicateIds = mutableListOf<Long>()

        for (existing in existingEvents) {
            val uid = existing.uid

            if (uid == null) {
                invalidOrDuplicateIds.add(existing.id)
                continue
            }

            val prev = existingByUid[uid]
            if (prev == null) {
                existingByUid[uid] = existing
            } else {
                invalidOrDuplicateIds.add(existing.id)
            }
        }

        val ops = ArrayList<ContentProviderOperation>()
        val currentUids = HashSet<String>()

        var insertCount = 0
        var updateCount = 0
        var reminderRefreshCount = 0
        var deleteCount = 0

        for (item in currentItems) {
            currentUids.add(item.uid)

            val existing = existingByUid[item.uid]

            if (existing == null) {
                val eventIndex = ops.size

                ops.add(
                    ContentProviderOperation
                        .newInsert(CalendarContract.Events.CONTENT_URI)
                        .withValues(buildEventValues(context, calendarId, item))
                        .build()
                )

                addReminderOps(ops, eventId = null, eventIndex = eventIndex)
                insertCount++
            } else {
                val changed = hasChanged(existing, item)

                if (changed) {
                    ops.add(
                        ContentProviderOperation
                            .newUpdate(CalendarContract.Events.CONTENT_URI)
                            .withSelection(
                                "${CalendarContract.Events._ID} = ?",
                                arrayOf(existing.id.toString())
                            )
                            .withValues(buildEventValues(context, calendarId, item))
                            .build()
                    )

                    updateCount++
                }

                if (changed || ensureAllReminders) {
                    ops.add(
                        ContentProviderOperation
                            .newDelete(CalendarContract.Reminders.CONTENT_URI)
                            .withSelection(
                                "${CalendarContract.Reminders.EVENT_ID} = ?",
                                arrayOf(existing.id.toString())
                            )
                            .build()
                    )

                    addReminderOps(ops, eventId = existing.id, eventIndex = null)
                    reminderRefreshCount++
                }
            }
        }

        if (allowDeleteMissing) {
            // Remove eventos antigos sem UID ou duplicados
            for (eventId in invalidOrDuplicateIds) {
                addDeleteEventOps(ops, eventId)
                deleteCount++
            }

            // Remove eventos que não existem mais na FEI/Moodle
            for (existing in existingByUid.values) {
                val uid = existing.uid ?: continue

                if (!currentUids.contains(uid)) {
                    addDeleteEventOps(ops, existing.id)
                    deleteCount++
                }
            }
        }

        try {
            if (ops.isNotEmpty()) {
                context.contentResolver.applyBatch(CalendarContract.AUTHORITY, ops)
            }

            Log.d(
                TAG,
                "syncEventsIncremental concluído | inserted=$insertCount | updated=$updateCount | remindersRefreshed=$reminderRefreshCount | deleted=$deleteCount"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao aplicar batch incremental na agenda", e)
        }
    }

    private fun buildEventItems(
        eventos: List<ProvaCalendario>,
        currentYear: Int
    ): List<CalendarEventItem> {
        data class ParsedEvent(
            val prova: ProvaCalendario,
            val start: ZonedDateTime,
            val end: ZonedDateTime
        )

        val parsedEvents = mutableListOf<ParsedEvent>()

        for (prova in eventos) {
            val start = parseStart(prova, currentYear) ?: continue
            val end = parseEnd(prova, start)

            if (!end.isAfter(start)) continue

            parsedEvents.add(ParsedEvent(prova, start, end))
        }

        val sorted = parsedEvents.sortedWith(
            compareBy(
                { stableKey(it.prova) },
                { it.start.toEpochSecond() },
                { it.prova.nomeDisciplina }
            )
        )

        val occurrenceCount = HashMap<String, Int>()
        val result = mutableListOf<CalendarEventItem>()

        for (parsed in sorted) {
            val key = stableKey(parsed.prova)

            val occurrence = occurrenceCount[key] ?: 0
            occurrenceCount[key] = occurrence + 1

            val uidSource = "$key#$occurrence"
            val uid = UUID.nameUUIDFromBytes(uidSource.toByteArray()).toString()

            result.add(
                CalendarEventItem(
                    uid = uid,
                    prova = parsed.prova,
                    start = parsed.start,
                    end = parsed.end,
                    title = buildTitle(parsed.prova),
                    description = buildDescription(parsed.prova),
                    location = parsed.prova.sala.orEmpty()
                )
            )
        }

        return result
    }

    private fun stableKey(prova: ProvaCalendario): String {
        return listOf(
            prova.tipoProva,
            prova.disciplina,
            prova.nomeDisciplina,
            prova.dataProva,
            prova.hora,
            prova.sala.orEmpty(),
            prova.coordenador
        ).joinToString(separator = "|")
    }

    private fun parseStart(prova: ProvaCalendario, currentYear: Int): ZonedDateTime? {
        val dateParts = prova.dataProva.trim().split("/")
        if (dateParts.size < 2) return null

        val day = dateParts[0].toIntOrNull() ?: return null
        val month = dateParts[1].toIntOrNull() ?: return null

        val year = if (dateParts.size >= 3) {
            val parsedYear = dateParts[2].toIntOrNull() ?: return null

            // Única restrição: pertencer ao mesmo ano
            if (parsedYear != currentYear) {
                return null
            }

            parsedYear
        } else {
            currentYear
        }

        val timeRegex = Regex("(\\d{2}):(\\d{2})")
        val match = timeRegex.find(prova.hora)

        val hour = match?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
        val minute = match?.groupValues?.getOrNull(2)?.toIntOrNull() ?: 0

        return try {
            ZonedDateTime.of(
                LocalDateTime.of(year, month, day, hour, minute, 0),
                ZoneId.systemDefault()
            )
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao parsear data/hora: ${prova.dataProva} ${prova.hora}", e)
            null
        }
    }

    private fun parseEnd(prova: ProvaCalendario, start: ZonedDateTime): ZonedDateTime {
        return try {
            val times = Regex("(\\d{2}:\\d{2})")
                .findAll(prova.hora)
                .map { it.value }
                .toList()

            if (times.size >= 2) {
                val endParts = times[1].split(":")
                val endHour = endParts[0].toIntOrNull() ?: return start.plusHours(1)
                val endMinute = endParts[1].toIntOrNull() ?: return start.plusHours(1)

                var end = start
                    .withHour(endHour)
                    .withMinute(endMinute)
                    .withSecond(0)
                    .withNano(0)

                if (!end.isAfter(start)) {
                    end = end.plusDays(1)
                }

                end
            } else {
                start.plusHours(1)
            }
        } catch (_: Exception) {
            start.plusHours(1)
        }
    }

    private fun buildTitle(prova: ProvaCalendario): String {
        return if (prova.tipoProva == "Moodle") {
            prova.nomeDisciplina
        } else {
            "${prova.disciplina} - ${prova.nomeDisciplina} (${prova.tipoProva})"
        }
    }

    private fun buildDescription(prova: ProvaCalendario): String {
        return buildString {
            append("Tipo: ").append(prova.tipoProva)

            if (prova.coordenador.isNotBlank()) {
                append("\nCoordenador: ").append(prova.coordenador)
            }

            if (prova.tipoProva != "Moodle" && prova.disciplina.isNotBlank()) {
                append("\nCódigo: ").append(prova.disciplina)
            }
        }
    }

    private fun buildEventValues(
        context: Context,
        calendarId: Long,
        item: CalendarEventItem
    ): ContentValues {
        return ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.UID_2445, item.uid)
            put(CalendarContract.Events.CUSTOM_APP_URI, "$EVENT_URI_PREFIX${item.uid}")
            put(CalendarContract.Events.CUSTOM_APP_PACKAGE, context.packageName)
            put(CalendarContract.Events.TITLE, item.title)
            put(CalendarContract.Events.DESCRIPTION, item.description)
            put(CalendarContract.Events.EVENT_LOCATION, item.location)
            put(CalendarContract.Events.DTSTART, item.start.toInstant().toEpochMilli())
            put(CalendarContract.Events.DTEND, item.end.toInstant().toEpochMilli())
            put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
            put(CalendarContract.Events.ALL_DAY, 0)
            put(CalendarContract.Events.HAS_ALARM, 1)
            put(CalendarContract.Events.AVAILABILITY, CalendarContract.Events.AVAILABILITY_BUSY)
            put(CalendarContract.Events.STATUS, CalendarContract.Events.STATUS_CONFIRMED)
        }
    }

    private fun addReminderOps(
        ops: ArrayList<ContentProviderOperation>,
        eventId: Long?,
        eventIndex: Int?
    ) {
        REMINDER_MINUTES.forEach { minutes ->
            val builder = ContentProviderOperation.newInsert(CalendarContract.Reminders.CONTENT_URI)

            if (eventId != null) {
                builder.withValue(CalendarContract.Reminders.EVENT_ID, eventId)
            } else if (eventIndex != null) {
                builder.withValueBackReference(CalendarContract.Reminders.EVENT_ID, eventIndex)
            }

            builder.withValue(CalendarContract.Reminders.MINUTES, minutes)
            builder.withValue(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)

            ops.add(builder.build())
        }
    }

    private fun addDeleteEventOps(
        ops: ArrayList<ContentProviderOperation>,
        eventId: Long
    ) {
        ops.add(
            ContentProviderOperation
                .newDelete(CalendarContract.Reminders.CONTENT_URI)
                .withSelection(
                    "${CalendarContract.Reminders.EVENT_ID} = ?",
                    arrayOf(eventId.toString())
                )
                .build()
        )

        ops.add(
            ContentProviderOperation
                .newDelete(CalendarContract.Events.CONTENT_URI)
                .withSelection(
                    "${CalendarContract.Events._ID} = ?",
                    arrayOf(eventId.toString())
                )
                .build()
        )
    }

    private fun queryExistingEvents(context: Context, calendarId: Long): List<ExistingEvent> {
        val projection = arrayOf(
            CalendarContract.Events._ID,
            CalendarContract.Events.UID_2445,
            CalendarContract.Events.CUSTOM_APP_URI,
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DESCRIPTION,
            CalendarContract.Events.EVENT_LOCATION,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.DTEND
        )

        val selection = "${CalendarContract.Events.CALENDAR_ID} = ?"
        val args = arrayOf(calendarId.toString())

        return try {
            context.contentResolver.query(
                CalendarContract.Events.CONTENT_URI,
                projection,
                selection,
                args,
                null
            )?.use { cursor ->
                val list = mutableListOf<ExistingEvent>()

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(0)
                    val uidColumn = getStringFromCursor(cursor, 1)
                    val customUri = getStringFromCursor(cursor, 2)
                    val title = getStringFromCursor(cursor, 3)
                    val description = getStringFromCursor(cursor, 4)
                    val location = getStringFromCursor(cursor, 5)
                    val dtStart = getLongFromCursor(cursor, 6)
                    val dtEnd = getLongFromCursor(cursor, 7)

                    list.add(
                        ExistingEvent(
                            id = id,
                            uid = extractUid(uidColumn, customUri),
                            title = title,
                            description = description,
                            location = location,
                            dtStart = dtStart,
                            dtEnd = dtEnd
                        )
                    )
                }

                list
            } ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao consultar eventos existentes", e)
            emptyList()
        }
    }

    private fun getStringFromCursor(cursor: Cursor, index: Int): String {
        return if (cursor.isNull(index)) "" else cursor.getString(index) ?: ""
    }

    private fun getLongFromCursor(cursor: Cursor, index: Int): Long {
        return if (cursor.isNull(index)) 0L else cursor.getLong(index)
    }

    private fun extractUid(uidColumn: String?, customUri: String?): String? {
        if (!uidColumn.isNullOrBlank()) {
            return uidColumn.trim()
        }

        if (customUri != null && customUri.startsWith(EVENT_URI_PREFIX)) {
            val candidate = customUri.removePrefix(EVENT_URI_PREFIX).trim()

            if (candidate.isNotBlank()) {
                return try {
                    UUID.fromString(candidate)
                    candidate
                } catch (_: Exception) {
                    candidate
                }
            }
        }

        return null
    }

    private fun hasChanged(existing: ExistingEvent, item: CalendarEventItem): Boolean {
        return existing.title != item.title ||
                existing.description != item.description ||
                existing.location != item.location ||
                existing.dtStart != item.start.toInstant().toEpochMilli() ||
                existing.dtEnd != item.end.toInstant().toEpochMilli()
    }

    private fun ensureCalendar(context: Context): Long? {
        val existing = getCalendarId(context)
        if (existing != null) {
            Log.d(TAG, "Calendário interno já existente | id=$existing")
            return existing
        }

        Log.d(TAG, "Calendário interno não encontrado, tentando criar...")

        val createdAsSyncAdapter = createCalendar(context, asSyncAdapter = true)
        if (createdAsSyncAdapter != null) {
            Log.d(TAG, "Calendário interno criado como sync adapter | id=$createdAsSyncAdapter")
            return createdAsSyncAdapter
        }

        val createdNormal = createCalendar(context, asSyncAdapter = false)
        if (createdNormal != null) {
            Log.d(TAG, "Calendário interno criado em modo normal | id=$createdNormal")
            return createdNormal
        }

        Log.e(TAG, "Falha ao criar calendário interno")
        return null
    }

    private fun getCalendarId(context: Context): Long? {
        return try {
            val projection = arrayOf(CalendarContract.Calendars._ID)
            val selection =
                "${CalendarContract.Calendars.ACCOUNT_NAME} = ? AND ${CalendarContract.Calendars.ACCOUNT_TYPE} = ?"
            val args = arrayOf(ACCOUNT_NAME, ACCOUNT_TYPE)

            context.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                projection,
                selection,
                args,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getLong(0)
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao buscar calendário interno", e)
            null
        }
    }

    private fun createCalendar(context: Context, asSyncAdapter: Boolean): Long? {
        return try {
            val values = ContentValues().apply {
                put(CalendarContract.Calendars.ACCOUNT_NAME, ACCOUNT_NAME)
                put(CalendarContract.Calendars.ACCOUNT_TYPE, ACCOUNT_TYPE)
                put(CalendarContract.Calendars.NAME, CALENDAR_NAME)
                put(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, CALENDAR_NAME)
                put(CalendarContract.Calendars.CALENDAR_COLOR, CALENDAR_COLOR)
                put(
                    CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
                    CalendarContract.Calendars.CAL_ACCESS_OWNER
                )
                put(CalendarContract.Calendars.OWNER_ACCOUNT, ACCOUNT_NAME)
                put(CalendarContract.Calendars.VISIBLE, 1)
                put(CalendarContract.Calendars.SYNC_EVENTS, 1)
                put(CalendarContract.Calendars.CALENDAR_TIME_ZONE, TimeZone.getDefault().id)
            }

            val uri = if (asSyncAdapter) {
                CalendarContract.Calendars.CONTENT_URI
                    .buildUpon()
                    .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
                    .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, ACCOUNT_NAME)
                    .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, ACCOUNT_TYPE)
                    .build()
            } else {
                CalendarContract.Calendars.CONTENT_URI
            }

            val result = context.contentResolver.insert(uri, values)
            result?.lastPathSegment?.toLongOrNull()
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao criar calendário interno asSyncAdapter=$asSyncAdapter", e)
            null
        }
    }

    private fun deleteAllAppEvents(context: Context) {
        try {
            val calendarId = getCalendarId(context)

            if (calendarId != null) {
                val existing = queryExistingEvents(context, calendarId)

                existing.forEach { event ->
                    context.contentResolver.delete(
                        CalendarContract.Reminders.CONTENT_URI,
                        "${CalendarContract.Reminders.EVENT_ID} = ?",
                        arrayOf(event.id.toString())
                    )
                }

                val deletedByCalendar = context.contentResolver.delete(
                    CalendarContract.Events.CONTENT_URI,
                    "${CalendarContract.Events.CALENDAR_ID} = ?",
                    arrayOf(calendarId.toString())
                )

                Log.d(TAG, "deleteAllAppEvents by calendarId=$calendarId | deleted=$deletedByCalendar")
            }

            val deletedByPackage = context.contentResolver.delete(
                CalendarContract.Events.CONTENT_URI,
                "${CalendarContract.Events.CUSTOM_APP_PACKAGE} = ?",
                arrayOf(context.packageName)
            )

            Log.d(
                TAG,
                "deleteAllAppEvents by packageName=${context.packageName} | deleted=$deletedByPackage"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao apagar todos os eventos do app da agenda", e)
        }
    }
}