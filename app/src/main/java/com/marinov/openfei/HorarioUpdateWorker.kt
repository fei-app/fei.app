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

class HorarioUpdateWorker(appContext: Context, workerParams: WorkerParameters) :
    CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "HorarioUpdateWorker"
        const val EXTRA_DESTINATION = "destination"
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        Log.d(TAG, "Worker iniciado. Verificando novos horários de aula...")

        // Inicializa o objeto Dados com o contexto da aplicação
        Dados.init(applicationContext)

        return@withContext try {
            // Chama o método que retorna true se houve alteração no horário
            val horarioAlterado = Dados.novoHorario(online = true)

            if (horarioAlterado) {
                Log.d(TAG, "Alteração no horário de aula detectada.")
                sendNotification()
            } else {
                Log.d(TAG, "Nenhuma alteração no horário de aula encontrada.")
            }

            Result.success()
        } catch (_: SessionExpiredException) {
            Log.w(TAG, "Sessão expirada ao verificar horários. Nenhuma notificação será enviada.")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao verificar horários", e)
            // Em caso de erro de rede ou outro, tenta novamente mais tarde
            Result.retry()
        }
    }

    private fun sendNotification() {
        // Intent para abrir a MainActivity e direcionar para o fragmento de horários
        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra(EXTRA_DESTINATION, "horarios")
        }

        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val channelId = "calendar_update_channel"
        val notification = NotificationCompat.Builder(applicationContext, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Novo horário de aula disponível!")
            .setContentText("Clique para ver detalhes")
            .setStyle(NotificationCompat.BigTextStyle().bigText("O horário de aulas foi atualizado. Confira as mudanças."))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager.createNotificationChannel(
                NotificationChannel(channelId, "Atualizações do horário de aula", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }

        notificationManager.notify(System.currentTimeMillis().toInt(), notification)
    }
}