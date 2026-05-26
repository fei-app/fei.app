package com.marinov.openfei

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class NotasWorker(appContext: Context, workerParams: WorkerParameters) :
    CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "NotasWorker"
        const val EXTRA_DESTINATION = "destination"
        private const val NOTIFICATION_ID = 4001 // Correção: ID fixo e único
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        Log.d(TAG, "Worker iniciado. Verificando novas notas...")

        Dados.init(applicationContext)

        return@withContext try {
            val notasAlteradas = Dados.atualizarNotas(online = true)

            if (notasAlteradas.isNotEmpty()) {
                Log.d(TAG, "${notasAlteradas.size} notas novas/alteradas detectadas.")
                sendNotification(notasAlteradas)
            } else {
                Log.d(TAG, "Nenhuma nota nova ou alterada encontrada.")
            }

            Result.success()
        } catch (_: SessionExpiredException) {
            Log.w(TAG, "Sessão expirada ao verificar notas. Enfileirando LoginWorker...")
            // Correção: Se a sessão expirar, agenda o renovamento de login e retenta
            WorkManager.getInstance(applicationContext).enqueue(
                OneTimeWorkRequestBuilder<LoginWorker>().build()
            )
            Result.retry()
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao verificar notas", e)
            Result.retry()
        }
    }

    private fun sendNotification(notasAlteradas: List<Dados.Nota>) {
        val notificationText = buildString {
            notasAlteradas.forEach { nota ->
                append("${nota.nomeDisciplina} (${nota.codigoDisciplina}) - ${nota.tipoProva}: ${nota.valor}\n")
            }
        }.trim()

        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra(EXTRA_DESTINATION, "notas")
        }

        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val channelId = "notas_channel"
        val notification = NotificationCompat.Builder(applicationContext, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Novas notas disponíveis!")
            .setContentText("Clique para ver detalhes")
            .setStyle(NotificationCompat.BigTextStyle().bigText(notificationText))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager.createNotificationChannel(
                NotificationChannel(channelId, "Atualizações de Notas", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }

        notificationManager.notify(NOTIFICATION_ID, notification)
    }
}