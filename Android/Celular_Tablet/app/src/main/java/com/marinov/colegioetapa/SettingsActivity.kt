package com.marinov.colegioetapa

import android.annotation.SuppressLint
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
import com.google.android.material.switchmaterial.SwitchMaterial
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

class SettingsActivity : AppCompatActivity() {

    private val tag = "SettingsActivity"
    private val coroutineScope = CoroutineScope(Dispatchers.Main + Job())
    private var progressBar: ProgressBar? = null

    companion object {
        const val PREFS_APP = "app_prefs"
        const val KEY_SAFE_MODE = "safe_mode"
    }

    private fun isSafeMode(): Boolean =
        getSharedPreferences(PREFS_APP, MODE_PRIVATE).getBoolean(KEY_SAFE_MODE, true)

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
        val safeModeActive = isSafeMode()

        val btnCheck = findViewById<Button>(R.id.btn_check_update)
        val btnClear = findViewById<Button>(R.id.btn_clear_data)
        val btnClearPassword = findViewById<Button>(R.id.btn_clear_password)
        val btnGithub = findViewById<Button>(R.id.btn_github)

        // GitHub e Verificar atualizações: sempre habilitados
        btnGithub.setOnClickListener { openUrl("https://github.com/etapaapp/") }
        btnCheck.setOnClickListener { checkUpdate() }

        // Limpar dados e senhas: bloqueados no modo de segurança
        if (safeModeActive) {
            btnClear.isEnabled = false
            btnClear.alpha = 0.38f
            btnClearPassword.isEnabled = false
            btnClearPassword.alpha = 0.38f
        } else {
            btnClear.isEnabled = true
            btnClear.alpha = 1.0f
            btnClearPassword.isEnabled = true
            btnClearPassword.alpha = 1.0f

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

        // Toggle de funcionalidade total
        val switchFullFeature = findViewById<SwitchMaterial>(R.id.switch_full_feature)
        val rowFullFeature = findViewById<View>(R.id.row_total_feature)
        switchFullFeature.isChecked = !safeModeActive

        rowFullFeature.setOnClickListener {
            if (isSafeMode()) {
                showActivateFullFeatureDialog()
            } else {
                showDeactivateFullFeatureDialog()
            }
        }
    }

    /** Diálogo Material 3 para ATIVAR funcionalidade total */
    private fun showActivateFullFeatureDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Ativar funcionalidade total")
            .setMessage(
                "Ao ativar funcionalidade total, o app poderá se conectar aos servidores do " +
                        "Colégio Etapa para obter dados como notas, calendário de provas e outros. " +
                        "Foi notada instabilidade após 01/04/2026 em tal funcionalidade, por isso " +
                        "encontra-se desativada por padrão.\n\nDeseja continuar?"
            )
            .setPositiveButton("Sim") { _, _ ->
                getSharedPreferences(PREFS_APP, MODE_PRIVATE).edit {
                    putBoolean(KEY_SAFE_MODE, false)
                }
                restartApp()
            }
            .setNegativeButton("Não", null)
            .show()
    }

    /** Diálogo Material 3 para DESATIVAR funcionalidade total (voltar ao modo seguro) */
    private fun showDeactivateFullFeatureDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Desativar funcionalidade total")
            .setMessage("O app será reiniciado.")
            .setPositiveButton("OK") { _, _ ->
                getSharedPreferences(PREFS_APP, MODE_PRIVATE).edit {
                    putBoolean(KEY_SAFE_MODE, true)
                }
                restartApp()
            }
            .show()
    }

    private fun restartApp() {
        val intent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
        finishAffinity()
    }

    private fun clearAllCacheData() {
        listOf(
            "horarios_prefs", "calendario_prefs", "materia_cache", "notas_prefs",
            "HomeFragmentCache", "provas_prefs", "redacao_detalhes_prefs",
            "cache_html_redacao_detalhes", "redacoes_prefs", "cache_html_redacoes",
            "material_prefs", "cache_html_material", "KEY_FILTRO", "graficos_prefs",
            "cache_html_graficos", "boletim_prefs", "cache_html_boletim",
            "redacao_semanal_prefs", "cache_html_redacao_semanal", "detalhes_prefs",
            "cache_html_horarios", "cache_alert_message", "cache_html_detalhes",
            "profile_preferences", "cache_html_provas"
        ).forEach { clearSharedPreferences(it) }
    }

    private fun clearAutoFill() {
        clearSharedPreferences("autofill_prefs")
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

    private fun checkUpdate() = coroutineScope.launch {
        try {
            val (json, responseCode) = withContext(Dispatchers.IO) {
                val url = URL("https://api.github.com/repos/etapaapp/EtapaApp/releases/latest")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.setRequestProperty("Accept", "application/vnd.github.v3+json")
                connection.setRequestProperty("User-Agent", "EtapaApp-Android")
                connection.connectTimeout = 10000
                connection.connect()
                try {
                    if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                        connection.inputStream.use { input ->
                            JSONObject(input.readText()) to connection.responseCode
                        }
                    } else {
                        null to connection.responseCode
                    }
                } finally {
                    connection.disconnect()
                }
            }
            if (json != null) processReleaseData(json)
            else showError("Erro na conexão: Código $responseCode")
        } catch (e: Exception) {
            Log.e(tag, "Erro na verificação", e)
            showError("Erro: ${e.message}")
        }
    }

    private fun InputStream.readText(): String =
        BufferedReader(InputStreamReader(this)).use { it.readText() }

    private fun processReleaseData(release: JSONObject) {
        runOnUiThread {
            val latest = release.getString("tag_name")
            val current = BuildConfig.VERSION_NAME
            if (UpdateChecker.isVersionGreater(latest, current)) {
                val assets = release.getJSONArray("assets")
                var apkUrl: String? = null
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    if (asset.getString("name").endsWith(".apk")) {
                        apkUrl = asset.getString("browser_download_url")
                        break
                    }
                }
                apkUrl?.let { promptForUpdate(it) } ?: showError("Arquivo APK não encontrado no release.")
            } else {
                showMessage()
            }
        }
    }

    private fun promptForUpdate(url: String) {
        runOnUiThread {
            MaterialAlertDialogBuilder(this)
                .setTitle("Atualização Disponível")
                .setMessage("Deseja baixar e instalar a versão mais recente?")
                .setPositiveButton("Sim") { _, _ -> startManualDownload(url) }
                .setNegativeButton("Não", null)
                .show()
        }
    }

    private fun startManualDownload(apkUrl: String) {
        coroutineScope.launch {
            val progressDialog = createProgressDialog().apply { show() }
            try {
                val apkFile = withContext(Dispatchers.IO) { downloadApk(apkUrl) }
                progressDialog.dismiss()
                apkFile?.let(::showInstallDialog) ?: showError("Falha ao baixar o arquivo.")
            } catch (e: Exception) {
                progressDialog.dismiss()
                Log.e(tag, "Erro no download", e)
                showError("Falha no download: ${e.message}")
            }
        }
    }

    @SuppressLint("InflateParams")
    private fun createProgressDialog(): android.app.AlertDialog {
        val view = layoutInflater.inflate(R.layout.dialog_download_progress, null)
        progressBar = view.findViewById(R.id.progress_bar)
        return android.app.AlertDialog.Builder(this)
            .setView(view)
            .setCancelable(false)
            .create()
    }

    private suspend fun downloadApk(apkUrl: String): File? = withContext(Dispatchers.IO) {
        try {
            val connection = URL(apkUrl).openConnection() as HttpURLConnection
            connection.connect()
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val outputDir = File(downloadsDir, "EtapaApp").apply {
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
            Log.e(tag, "Erro no download", e); null
        }
    }

    private fun showInstallDialog(apkFile: File) {
        runOnUiThread {
            try {
                if (!apkFile.exists()) { showError("Arquivo APK não encontrado"); return@runOnUiThread }
                val apkUri = FileProvider.getUriForFile(
                    this@SettingsActivity,
                    "${BuildConfig.APPLICATION_ID}.provider",
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
                showError("Erro ao iniciar a instalação: ${e.message}")
            }
        }
    }

    private fun showMessage() {
        runOnUiThread {
            MaterialAlertDialogBuilder(this)
                .setMessage("Você já está na versão mais recente")
                .setPositiveButton("OK", null)
                .show()
        }
    }

    private fun showError(msg: String) {
        runOnUiThread {
            MaterialAlertDialogBuilder(this)
                .setTitle("Erro")
                .setMessage(msg)
                .setPositiveButton("OK", null)
                .show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        coroutineScope.cancel()
        progressBar = null
    }
}