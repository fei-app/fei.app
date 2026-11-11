package com.marinov.openfei.update

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Environment
import android.util.Log
import android.widget.ProgressBar
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.marinov.openfei.BuildConfig
import com.marinov.openfei.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

class AppUpdateDownloader(
    private val activity: AppCompatActivity,
    private val scope: CoroutineScope
) {

    private companion object {
        const val TAG = "AppUpdateDownloader"
    }

    var isDownloading: Boolean = false
        private set

    private var progressBar: ProgressBar? = null

    fun startDownload(apkUrl: String, forced: Boolean) {
        if (isDownloading || activity.isFinishing || activity.isDestroyed) return
        isDownloading = true
        val progressDialog = createProgressDialog().apply { show() }
        scope.launch {
            try {
                val apkFile = withContext(Dispatchers.IO) { downloadApk(apkUrl) }
                dismissDialogIfAlive(progressDialog)
                isDownloading = false
                if (apkFile != null) {
                    showInstallDialog(apkFile, forced)
                } else {
                    showErrorDialog(activity.getString(R.string.settings_update_erro_download))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                dismissDialogIfAlive(progressDialog)
                isDownloading = false
                Log.e(TAG, activity.getString(R.string.settings_erro_download_log), e)
                showErrorDialog(
                    activity.getString(
                        R.string.settings_update_erro_download_msg,
                        e.message ?: ""
                    )
                )
            }
        }
    }

    fun destroy() {
        progressBar = null
    }

    private fun dismissDialogIfAlive(dialog: AlertDialog) {
        if (!activity.isFinishing && !activity.isDestroyed) {
            try {
                dialog.dismiss()
            } catch (_: Exception) {
            }
        }
    }

    @SuppressLint("InflateParams")
    private fun createProgressDialog(): AlertDialog {
        val view = activity.layoutInflater.inflate(R.layout.dialog_download_progress, null)
        progressBar = view.findViewById(R.id.progress_bar)
        return MaterialAlertDialogBuilder(activity)
            .setView(view)
            .setCancelable(false)
            .create()
    }

    /**
     * ★ BUG 17: verifica responseCode antes de ler o corpo. Um 404/500/portal
     * cativo antes salvava HTML como "app_release.apk" e oferecia "Instalar".
     * ★ BUG 60: atualiza o progresso apenas quando o inteiro muda,
     * evitando milhares de dispatches para a Main em APKs de 20+ MB.
     */
    private suspend fun downloadApk(apkUrl: String): File? = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            connection = URL(apkUrl).openConnection() as HttpURLConnection
            connection.connectTimeout = 15000
            connection.readTimeout = 30000
            connection.connect()

            val code = connection.responseCode
            if (code !in 200..299) {
                Log.e(TAG, "HTTP $code ao baixar APK de $apkUrl")
                return@withContext null
            }

            val downloadsDir = activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                ?: return@withContext null
            val outputDir = File(downloadsDir, "Update").apply {
                if (exists()) deleteRecursively()
                mkdirs()
            }
            val outputFile = File(outputDir, "app_release.apk")

            connection.inputStream.use { input ->
                FileOutputStream(outputFile).use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    var total: Long = 0
                    var lastReportedProgress = -1
                    val fileLength = connection.contentLengthLong
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        total += bytesRead
                        if (fileLength > 0) {
                            val progress = (total * 100 / fileLength).toInt()
                            if (progress != lastReportedProgress) {
                                lastReportedProgress = progress
                                withContext(Dispatchers.Main) {
                                    progressBar?.progress = progress
                                }
                            }
                        }
                    }
                }
            }
            outputFile
        } catch (e: Exception) {
            Log.e(TAG, "Erro no download.", e)
            null
        } finally {
            runCatching { connection?.disconnect() }
        }
    }

    private fun showInstallDialog(apkFile: File, forced: Boolean) {
        if (activity.isFinishing || activity.isDestroyed) return
        try {
            if (!apkFile.exists()) {
                showErrorDialog(activity.getString(R.string.settings_apk_nao_encontrado))
                return
            }
            val apkUri = FileProvider.getUriForFile(
                activity,
                "${BuildConfig.APPLICATION_ID}.fileprovider",
                apkFile
            )
            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (installIntent.resolveActivity(activity.packageManager) != null) {
                val builder = MaterialAlertDialogBuilder(activity)
                    .setTitle(activity.getString(R.string.settings_download_concluido))
                    .setMessage(activity.getString(R.string.settings_instalar_msg))
                    .setCancelable(false)
                    .setPositiveButton(activity.getString(R.string.instalar)) { _, _ ->
                        try {
                            activity.startActivity(installIntent)
                        } catch (e: Exception) {
                            Log.e(TAG, activity.getString(R.string.settings_erro_instalacao), e)
                            showErrorDialog(
                                activity.getString(
                                    R.string.settings_erro_instalacao_msg,
                                    e.message ?: ""
                                )
                            )
                        }
                    }
                    .setNegativeButton(activity.getString(R.string.cancelar), null)
                val dialog = builder.create()
                dialog.setCanceledOnTouchOutside(false)
                dialog.setOnShowListener {
                    if (forced) {
                        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.apply {
                            isEnabled = false
                            isClickable = false
                            alpha = 0.4f
                        }
                    }
                }
                dialog.show()
            } else {
                showErrorDialog(activity.getString(R.string.settings_nenhum_app_instalar))
            }
        } catch (e: Exception) {
            Log.e(TAG, activity.getString(R.string.settings_erro_instalacao), e)
            showErrorDialog(
                activity.getString(R.string.settings_erro_instalacao_msg, e.message ?: "")
            )
        }
    }

    private fun showErrorDialog(message: String) {
        if (activity.isFinishing || activity.isDestroyed) return
        MaterialAlertDialogBuilder(activity)
            .setTitle(activity.getString(R.string.erro))
            .setMessage(message)
            .setPositiveButton(activity.getString(R.string.ok), null)
            .show()
    }
}