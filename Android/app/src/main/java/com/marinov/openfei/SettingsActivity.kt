package com.marinov.openfei

import android.annotation.SuppressLint
import com.bumptech.glide.Glide
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.webkit.CookieManager
import android.widget.Button
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

class SettingsActivity : AppCompatActivity() {

    private val tag = "SettingsActivity"
    private val coroutineScope = CoroutineScope(Dispatchers.Main + Job())
    private var progressBar: ProgressBar? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        configureSystemBarsForLegacyDevices()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        setupToolbar()
        setupUI()

        if (intent.getBooleanExtra("open_update_directly", false)) {
            checkUpdate()
        }
    }

    private fun setupToolbar() {
        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        setupToolbarInsets()
    }

    @SuppressLint("ObsoleteSdkInt")
    private fun configureSystemBarsForLegacyDevices() {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            val isDarkMode = when (AppCompatDelegate.getDefaultNightMode()) {
                AppCompatDelegate.MODE_NIGHT_YES -> true
                AppCompatDelegate.MODE_NIGHT_NO -> false
                else -> {
                    val currentNightMode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
                    currentNightMode == Configuration.UI_MODE_NIGHT_YES
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                window.apply {
                    @Suppress("DEPRECATION")
                    clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS)
                    addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
                    if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.N_MR1) {
                        @Suppress("DEPRECATION")
                        statusBarColor = Color.BLACK
                        @Suppress("DEPRECATION")
                        navigationBarColor = Color.BLACK
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            @Suppress("DEPRECATION")
                            var flags = decorView.systemUiVisibility
                            @Suppress("DEPRECATION")
                            flags = flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
                            @Suppress("DEPRECATION")
                            decorView.systemUiVisibility = flags
                        }
                    } else {
                        @Suppress("DEPRECATION")
                        navigationBarColor = ContextCompat.getColor(this@SettingsActivity, R.color.fundocartao)
                    }
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                @Suppress("DEPRECATION")
                var flags = window.decorView.systemUiVisibility
                if (isDarkMode) {
                    @Suppress("DEPRECATION")
                    flags = flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
                } else if (Build.VERSION.SDK_INT > Build.VERSION_CODES.N_MR1) {
                    @Suppress("DEPRECATION")
                    flags = flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                }
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = flags
            }
            if (!isDarkMode && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                @Suppress("DEPRECATION")
                var flags = window.decorView.systemUiVisibility
                @Suppress("DEPRECATION")
                flags = flags or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = flags
            }
        }
    }

    private fun setupToolbarInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.toolbar)) { v, insets ->
            val statusBarHeight = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            v.setPadding(v.paddingLeft, statusBarHeight, v.paddingRight, v.paddingBottom)
            insets
        }
    }

    private fun setupUI() {
        val btnCheck = findViewById<Button>(R.id.btn_check_update)
        val btnClear = findViewById<Button>(R.id.btn_clear_data)
        val btnClearPassword = findViewById<Button>(R.id.btn_clear_password)
        val btnGitlab = findViewById<Button>(R.id.btn_gitlab)

        btnGitlab.setOnClickListener { openUrl("https://gitlab.com/fei.app/") }
        btnCheck.setOnClickListener { checkUpdate() }

        btnClear.setOnClickListener {
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
            clearAllCacheData()
            Toast.makeText(this, "Base de dados apagada com sucesso!", Toast.LENGTH_SHORT).show()
        }

        btnClearPassword.setOnClickListener {
            clearAutoFill()
            Toast.makeText(
                this,
                "Dados de preenchimento automático apagados com sucesso!",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun clearAllCacheData() {
        Dados.init(applicationContext)
        Dados.clearAllCacheFiles()

        getSharedPreferences("HomeFragmentCache", MODE_PRIVATE).edit().clear().apply()
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        android.webkit.WebStorage.getInstance().deleteAllData()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            android.webkit.WebView.clearClientCertPreferences(null)
        }
        val context = applicationContext
        android.webkit.WebView(context).apply {
            clearCache(true)
            clearHistory()
            clearFormData()
            destroy()
        }
        CoroutineScope(Dispatchers.IO).launch {
            Glide.get(context).clearDiskCache()
        }
        Glide.get(context).clearMemory()
    }

    private fun clearAutoFill() {
        clearSharedPreferences(LoginActivity.PREFS_LOGIN)
    }

    private fun clearSharedPreferences(name: String) {
        getSharedPreferences(name, MODE_PRIVATE).edit { clear() }
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
        } catch (e: Exception) {
            Log.e(tag, "Erro ao abrir URL", e)
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun checkUpdate() {
        UpdateChecker.checkForUpdate(this, true, object : UpdateChecker.UpdateListener {
            override fun onUpdateAvailable(url: String, version: String, releaseNotes: String) {
                runOnUiThread { promptForUpdate(url, version, releaseNotes) }
            }

            override fun onUpToDate() {
                runOnUiThread { showMessage() }
            }

            override fun onError(message: String) {
                runOnUiThread { showError(message) }
            }
        })
    }

    private fun promptForUpdate(url: String, version: String, releaseNotes: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Atualização Disponível ($version)")
            .setMessage("O que há de novo:\n\n$releaseNotes\n\nDeseja baixar e instalar agora?")
            .setPositiveButton("Sim") { _, _ -> startManualDownload(url) }
            .setNegativeButton("Não", null)
            .show()
    }

    private fun startManualDownload(apkUrl: String) {
        coroutineScope.launch {
            val progressDialog = createProgressDialog().apply { show() }
            try {
                val apkFile = withContext(Dispatchers.IO) { downloadApk(apkUrl) }
                progressDialog.dismiss()
                apkFile?.let(::showInstallDialog) ?: showError("Falha ao baixar o arquivo. Tente usar um VPN ou proxy ou baixar a atualização manualmente.")
            } catch (e: Exception) {
                progressDialog.dismiss()
                Log.e(tag, "Erro no download", e)
                showError("Falha no download: ${e.message}. Tente usar um VPN ou proxy ou baixar a atualização manualmente")
            }
        }
    }

    @SuppressLint("InflateParams")
    private fun createProgressDialog(): androidx.appcompat.app.AlertDialog {
        val view = layoutInflater.inflate(R.layout.dialog_download_progress, null)
        progressBar = view.findViewById(R.id.progress_bar)

        return MaterialAlertDialogBuilder(this)
            .setView(view)
            .setCancelable(false)
            .create()
    }

    private suspend fun downloadApk(apkUrl: String): File? = withContext(Dispatchers.IO) {
        try {
            val connection = URL(apkUrl).openConnection() as HttpURLConnection
            connection.connect()

            val downloadsDir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            val outputDir = File(downloadsDir, "Update").apply {
                if (exists()) deleteRecursively()
                mkdirs()
            }

            val outputFile = File(outputDir, "app_release.apk")

            connection.inputStream.use { input ->
                FileOutputStream(outputFile).use { output ->
                    val buffer = ByteArray(4096)
                    var bytesRead: Int
                    var total: Long = 0
                    val fileLength = connection.contentLength.toLong()
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        total += bytesRead
                        if (fileLength > 0) {
                            val progress = (total * 100 / fileLength).toInt()
                            withContext(Dispatchers.Main) { progressBar?.progress = progress }
                        }
                    }
                }
            }
            outputFile
        } catch (e: Exception) {
            Log.e(tag, "Erro no download.", e); null
        }
    }

    private fun showInstallDialog(apkFile: File) {
        runOnUiThread {
            try {
                if (!apkFile.exists()) { showError("Arquivo APK não encontrado"); return@runOnUiThread }
                val apkUri = FileProvider.getUriForFile(
                    this@SettingsActivity,
                    "${BuildConfig.APPLICATION_ID}.fileprovider",
                    apkFile
                )
                val installIntent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(apkUri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                if (installIntent.resolveActivity(packageManager) != null) {
                    MaterialAlertDialogBuilder(this@SettingsActivity)
                        .setTitle("Download concluído")
                        .setMessage("Deseja instalar a atualização agora?")
                        .setPositiveButton("Instalar") { _, _ -> startActivity(installIntent) }
                        .setNegativeButton("Cancelar", null)
                        .show()
                } else {
                    showError("Nenhum aplicativo encontrado para instalar o APK")
                }
            } catch (e: Exception) {
                Log.e(tag, "Erro na instalação", e)
                showError("Erro ao iniciar a instalação: ${e.message}.")
            }
        }
    }

    private fun showMessage() {
        MaterialAlertDialogBuilder(this)
            .setMessage("Você já está na versão mais recente")
            .setPositiveButton("OK", null)
            .show()
    }

    private fun showError(msg: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Erro")
            .setMessage(msg)
            .setPositiveButton("OK", null)
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        coroutineScope.cancel()
        progressBar = null
    }
}