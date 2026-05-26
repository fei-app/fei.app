package com.marinov.openfei

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object WorkerManagerHelper {

    fun iniciarWorkers(context: Context) {
        val appContext = context.applicationContext

        // Restrição: Executar apenas quando houver internet
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val updateWork = PeriodicWorkRequest.Builder(
            UpdateCheckWorker::class.java, 120, TimeUnit.MINUTES
        ).setConstraints(constraints).build()

        val notasWork = PeriodicWorkRequest.Builder(
            NotasWorker::class.java, 20, TimeUnit.MINUTES
        ).setConstraints(constraints).build()

        val calendarioWork = PeriodicWorkRequest.Builder(
            HorarioUpdateWorker::class.java, 20, TimeUnit.MINUTES
        ).setConstraints(constraints).build()

        val loginWork = PeriodicWorkRequest.Builder(
            LoginWorker::class.java, 15, TimeUnit.MINUTES
        ).setConstraints(constraints).build()

        val boletosWork = PeriodicWorkRequest.Builder(
            BoletosWorker::class.java, 20, TimeUnit.MINUTES
        ).setConstraints(constraints).build()

        val workManager = WorkManager.getInstance(appContext)

        // Usar a política KEEP garante que, se o worker já estiver agendado, não será duplicado.
        // Se o sistema esqueceu dele no boot, ele será recriado.
        workManager.enqueueUniquePeriodicWork("UpdateCheckWorker", ExistingPeriodicWorkPolicy.KEEP, updateWork)
        workManager.enqueueUniquePeriodicWork("NotasWorkerTask", ExistingPeriodicWorkPolicy.KEEP, notasWork)
        workManager.enqueueUniquePeriodicWork("CalendarioWorkerTask", ExistingPeriodicWorkPolicy.KEEP, calendarioWork)
        workManager.enqueueUniquePeriodicWork("LoginWorkerTask", ExistingPeriodicWorkPolicy.KEEP, loginWork)
        workManager.enqueueUniquePeriodicWork("BoletosWorkerTask", ExistingPeriodicWorkPolicy.KEEP, boletosWork)
    }
}