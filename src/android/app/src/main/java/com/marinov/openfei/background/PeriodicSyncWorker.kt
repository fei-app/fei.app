package com.marinov.openfei.background

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.marinov.openfei.app.AppMode
import com.marinov.openfei.cache.CacheHelper
import com.marinov.openfei.cache.SyncBaseline
import com.marinov.openfei.data.BoletosRepository
import com.marinov.openfei.data.CalendarioRepository
import com.marinov.openfei.data.DataInitializer
import com.marinov.openfei.data.HorariosRepository
import com.marinov.openfei.data.NotasRepository
import com.marinov.openfei.login.AuthState
import com.marinov.openfei.login.CredentialsStore
import com.marinov.openfei.login.RustSession
import com.marinov.openfei.login.SessionResult
import com.marinov.openfei.network.NetworkChecker
import kotlinx.coroutines.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class PeriodicSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    companion object {
        private const val TAG = "PeriodicSyncWorker"
        private const val WORK_ONE_SHOT = "openfei_sync_once"
        private const val KEY_SYNC_TYPE = "sync_type"
        private const val WORK_PERIODIC = "openfei_moodle_periodic"

        /**
         * ★ BUG 81: LoginActivity.syncNow(BOOT) + HomeFragment.maybeRequestImmediateSync
         * enfileiravam DOIS syncs boot; com APPEND_OR_REPLACE o segundo rodava
         * segundos depois do primeiro — e comparava o cache que o primeiro
         * tinha acabado de semear, disparando notificação na instalação limpa.
         * Agora pedidos do MESMO tipo dentro desta janela são ignorados.
         */
        private const val JANELA_DEDUPE_MS = 60_000L
        private val ultimoEnqueuePorTipo = ConcurrentHashMap<String, Long>()

        /**
         * ★ PROBLEMA 2: `APPEND_OR_REPLACE` em vez de `REPLACE`.
         * REPLACE cancelava o sync que tinha acabado de começar, gerando
         * JobCancellationException em NotasRepository/HorariosRepository/Boletos.
         */
        fun enqueueOnce(context: Context, type: String) {
            val agora = System.currentTimeMillis()
            val ultimo = ultimoEnqueuePorTipo[type] ?: 0L
            if (agora - ultimo < JANELA_DEDUPE_MS) {
                Log.d(TAG, "Sync '$type' duplicado (${agora - ultimo}ms) — ignorado")
                return
            }
            ultimoEnqueuePorTipo[type] = agora

            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = OneTimeWorkRequestBuilder<PeriodicSyncWorker>()
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.LINEAR, 1, TimeUnit.MINUTES)
                .setInputData(workDataOf(KEY_SYNC_TYPE to type))
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_ONE_SHOT,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                request
            )
        }

        fun schedulePeriodicBackup(context: Context, replace: Boolean = false) {
            val freqHours = SyncPrefs.moodleFrequencyHours.toLong().coerceAtLeast(1)
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<PeriodicSyncWorker>(
                freqHours, TimeUnit.HOURS, 15, TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .setInputData(workDataOf(KEY_SYNC_TYPE to SyncScheduler.TYPE_MOODLE))
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_PERIODIC,
                if (replace) ExistingPeriodicWorkPolicy.UPDATE else ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }

        fun cancelPeriodicBackup(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_PERIODIC)
        }
    }

    override suspend fun doWork(): Result {
        val type = inputData.getString(KEY_SYNC_TYPE) ?: return Result.failure()
        Log.d(TAG, "Iniciando sync tipo=$type")

        DataInitializer.init(applicationContext)

        // ★ Redundância do bug C: sempre garante que os alarmes existem.
        SyncScheduler.scheduleAll(applicationContext)

        if (!CredentialsStore.hasCredentials(applicationContext)) {
            Log.d(TAG, "Sync abortado: sem credenciais")
            return Result.success()
        }

        // ★ Backup periódico não pode sincronizar Moodle de madrugada.
        if (type == SyncScheduler.TYPE_MOODLE && !SyncScheduler.isInsideMoodleWindow()) {
            Log.d(TAG, "Moodle fora da janela 7h–23h (Brasília) — ignorado")
            return Result.success()
        }

        if (!isNetworkAllowed()) {
            Log.d(TAG, "Sync ignorado: rede limitada")
            return Result.success()
        }

        when (val r = RustSession.ensureFeiSession()) {
            is SessionResult.NetworkError -> {
                Log.w(TAG, "Sync adiado: ${r.message}")
                return Result.retry()
            }
            SessionResult.NoCredentials -> return Result.success()
            is SessionResult.AuthError -> {
                return if (AuthState.isAuthBlocked(applicationContext)) Result.failure()
                else Result.retry()
            }
            SessionResult.Ok -> Unit
        }

        // ★ BUG 80: diagnóstico da primeira execução (nada é notificado sem baseline).
        Log.d(
            TAG,
            "Baselines | notas=${SyncBaseline.isEstablished(applicationContext, SyncBaseline.NOTAS)}" +
                    " | aulas=${SyncBaseline.isEstablished(applicationContext, SyncBaseline.AULAS)}" +
                    " | boletos=${SyncBaseline.isEstablished(applicationContext, SyncBaseline.BOLETOS)}"
        )

        val isResponsavel = AppMode.isResponsavelFinanceiro

        return try {
            when (type) {
                SyncScheduler.TYPE_FEI -> runFeiSync(isResponsavel)
                SyncScheduler.TYPE_MOODLE -> runMoodleSync(isResponsavel)
                else -> runBootSync(isResponsavel)
            }
            Result.success()
        } catch (e: CancellationException) {
            Log.d(TAG, "Sync cancelado pelo sistema")
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Erro durante sync", e)
            Result.retry()
        }
    }

    private fun isNetworkAllowed(): Boolean {
        return !SyncPrefs.syncOnlyOnUnmeteredNetworks ||
                !NetworkChecker.isNetworkMetered(applicationContext)
    }

    private suspend fun runFeiSync(isResponsavel: Boolean) {
        var provasOk = false
        if (!isResponsavel) {
            runNotas()
            runHorario()
            provasOk = runProvasFei()
        }
        runBoletos()
        if (!isResponsavel) syncSystemCalendar(fonteAtualizadaOk = provasOk)
    }

    private suspend fun runMoodleSync(isResponsavel: Boolean) {
        if (isResponsavel) return
        val eventosOk = runEventosMoodle()
        syncSystemCalendar(fonteAtualizadaOk = eventosOk)
    }

    private suspend fun runBootSync(isResponsavel: Boolean) {
        var provasOk = false
        var eventosOk = false
        if (!isResponsavel) {
            runNotas()
            runHorario()
            provasOk = runProvasFei()
            eventosOk = runEventosMoodle()
        }
        runBoletos()
        if (!isResponsavel) syncSystemCalendar(fonteAtualizadaOk = provasOk && eventosOk)
    }

    /** ★ PROBLEMA 2b + BUG 80: try/catch com rethrow de CancellationException. */
    private suspend fun runNotas() {
        try {
            val alteradas = NotasRepository.atualizarNotas(online = true)
                .filter { it.valor.isNotBlank() }
            if (alteradas.isEmpty()) {
                Log.d(TAG, "Notas: nenhuma alteração real — sem notificação")
                return
            }
            Log.i(TAG, "Notas: ${alteradas.size} alteração(ões) — notificando")
            Notifications.sendNotas(applicationContext, alteradas)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Erro em runNotas", e)
        }
    }

    private suspend fun runBoletos() {
        try {
            if (BoletosRepository.atualizaBoletos()) {
                Log.i(TAG, "Boletos: alteração detectada — notificando")
                Notifications.sendBoletos(applicationContext)
            } else {
                Log.d(TAG, "Boletos: nenhuma alteração real — sem notificação")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Erro em runBoletos", e)
        }
    }

    private suspend fun runHorario() {
        try {
            if (HorariosRepository.novoHorario(online = true)) {
                Log.i(TAG, "Horário: alteração confirmada — notificando")
                Notifications.sendHorario(applicationContext)
            } else {
                Log.d(TAG, "Horário: nenhuma alteração real — sem notificação")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Erro em runHorario", e)
        }
    }

    /** ★ BUG 80: devolve true só com fetch online válido e não vazio. */
    private suspend fun runProvasFei(): Boolean {
        return try {
            !CalendarioRepository.obterProvasFEIOnlineOrNull().isNullOrEmpty()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Erro em runProvasFei", e)
            false
        }
    }

    private suspend fun runEventosMoodle(): Boolean {
        return try {
            !CalendarioRepository.obterEventosMoodleOnlineOrNull().isNullOrEmpty()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Erro em runEventosMoodle", e)
            false
        }
    }

    /**
     * ★ BUG 80: a agenda do sistema só pode APAGAR eventos quando as duas
     * fontes têm baseline confirmado e cache não vazio. Com dados nulos
     * (primeira execução, sessão expirada, erro de parse) o sync apenas
     * adiciona/atualiza — nunca deleta.
     */
    private suspend fun syncSystemCalendar(fonteAtualizadaOk: Boolean) {
        try {
            val provas = CacheHelper.getCachedProvasFEI()
            val eventos = CacheHelper.getCachedEventosMoodle()

            if (provas.isEmpty() && eventos.isEmpty()) {
                Log.i(TAG, "Agenda: cache vazio — nada a sincronizar e nada será apagado")
                return
            }

            val baselinesOk =
                SyncBaseline.isEstablished(applicationContext, SyncBaseline.PROVAS_FEI) &&
                        SyncBaseline.isEstablished(applicationContext, SyncBaseline.EVENTOS_MOODLE)

            val podeApagar = fonteAtualizadaOk && baselinesOk &&
                    provas.isNotEmpty() && eventos.isNotEmpty()

            Log.d(TAG, "Agenda: force=true | allowDeleteMissing=$podeApagar")

            CalendarSyncManager.syncCached(
                context = applicationContext,
                force = true,
                allowDeleteMissing = podeApagar
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao sincronizar agenda", e)
        }
    }
}