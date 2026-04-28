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
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class NotasWorker(appContext: Context, workerParams: WorkerParameters) :
    CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "NotasWorker"
        const val EXTRA_DESTINATION = "destination"
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        Log.d(TAG, "Worker iniciado. Verificando novas notas...")

        // Inicializa o objeto Dados com o contexto da aplicação
        Dados.init(applicationContext)

        return@withContext try {
            // Chama o método que retorna apenas as notas que foram alteradas (novas ou modificadas)
            val notasAlteradas = Dados.atualizarNotas(online = true)

            if (notasAlteradas.isNotEmpty()) {
                Log.d(TAG, "${notasAlteradas.size} notas novas/alteradas detectadas.")
                sendNotification(notasAlteradas)
            } else {
                Log.d(TAG, "Nenhuma nota nova ou alterada encontrada.")
            }

            Result.success()
        } catch (_: SessionExpiredException) {
            Log.w(TAG, "Sessão expirada ao verificar notas. Nenhuma notificação será enviada.")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao verificar notas", e)
            // Em caso de erro de rede ou outro, tenta novamente mais tarde
            Result.retry()
        }
    }

    private fun sendNotification(notasAlteradas: List<Dados.Nota>) {
        // Constrói o texto detalhado com as informações de cada nota alterada
        val notificationText = buildString {
            notasAlteradas.forEach { nota ->
                append("${nota.nomeDisciplina} (${nota.codigoDisciplina}) - ${nota.tipoProva}: ${nota.valor}\n")
            }
        }.trim()

        // Intent para abrir a MainActivity e direcionar para o fragmento de notas
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

        notificationManager.notify(System.currentTimeMillis().toInt(), notification)
    }
}