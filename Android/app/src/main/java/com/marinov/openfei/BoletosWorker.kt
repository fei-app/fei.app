package com.marinov.openfei

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class BoletosWorker(appContext: Context, workerParams: WorkerParameters) :
    CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "BoletosWorker"
        const val EXTRA_DESTINATION = "destination"
        private const val REQUEST_CODE_BOLETOS = 300
        private const val NOTIFICATION_ID = 3001 // Correção: ID fixo e único
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        Log.d(TAG, "Worker iniciado. Verificando alterações nos boletos...")

        Dados.init(applicationContext)

        return@withContext try {
            val houve = Dados.atualizaBoletos()

            if (houve) {
                Log.d(TAG, "Alteração nos boletos detectada.")
                sendNotification()
            } else {
                Log.d(TAG, "Nenhuma alteração nos boletos encontrada.")
            }

            Result.success()
        } catch (_: SessionExpiredException) {
            Log.w(TAG, "Sessão expirada ao verificar boletos. Enfileirando LoginWorker...")
            // Correção: Se a sessão expirar, agenda o renovamento de login e retenta
            WorkManager.getInstance(applicationContext).enqueue(
                OneTimeWorkRequestBuilder<LoginWorker>().build()
            )
            Result.retry()
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao verificar boletos", e)
            Result.retry()
        }
    }

    private fun sendNotification() {
        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra(EXTRA_DESTINATION, "boletos")
        }

        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            REQUEST_CODE_BOLETOS,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val channelId = "boletos_channel"
        val notification = NotificationCompat.Builder(applicationContext, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Boleto atualizado!")
            .setContentText("Houve alteração nos seus boletos. Toque para ver.")
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("Há novidades nos seus boletos da FEI. Confira se há algum boleto em aberto ou recém-pago.")
            )
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        val notificationManager =
            applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        notificationManager.createNotificationChannel(
            NotificationChannel(
                channelId,
                "Atualizações de Boletos",
                NotificationManager.IMPORTANCE_DEFAULT
            )
        )

        notificationManager.notify(NOTIFICATION_ID, notification)
    }
}