package com.marinov.openfei.ui.settings

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
import com.google.android.material.materialswitch.MaterialSwitch
import com.marinov.openfei.BuildConfig
import com.marinov.openfei.R
import com.marinov.openfei.app.AppMode
import com.marinov.openfei.data.Dados
import com.marinov.openfei.data.UpdateChecker
import com.marinov.openfei.ui.login.LoginActivity
import com.marinov.openfei.ui.main.MainActivity
import com.marinov.openfei.util.PermissionHelper
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
    private lateinit var switchModoResponsavel: MaterialSwitch

    // ★ Listener nomeado para poder ser desligado/religado ao reverter o toggle ★
    // CORREÇÃO: Adicionado o tipo explicitamente (android.widget.CompoundButton.OnCheckedChangeListener)
    private val modoResponsavelListener: android.widget.CompoundButton.OnCheckedChangeListener =
        android.widget.CompoundButton.OnCheckedChangeListener { switchView, isChecked ->
            switchView.setOnCheckedChangeListener(null)
            switchView.isChecked = !isChecked
            switchView.setOnCheckedChangeListener(modoResponsavelListener)
            confirmarMudancaModoResponsavel(isChecked)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        configureSystemBarsForLegacyDevices()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        setupToolbar()
        setupUI()
        setupModoResponsavelFinanceiro()
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
            Toast.makeText(this, getString(R.string.settings_base_apagada), Toast.LENGTH_SHORT).show()
        }
        btnClearPassword.setOnClickListener {
            clearAutoFill()
            Toast.makeText(this, getString(R.string.settings_autofill_apagado), Toast.LENGTH_SHORT).show()
        }
    }

    // ================= Modo Responsável Financeiro =================

    private fun setupModoResponsavelFinanceiro() {
        switchModoResponsavel = findViewById(R.id.switch_modo_responsavel)
        switchModoResponsavel.isChecked = AppMode.isResponsavelFinanceiro
        switchModoResponsavel.setOnCheckedChangeListener(modoResponsavelListener)
    }

    private fun confirmarMudancaModoResponsavel(ativar: Boolean) {
        val mensagem = if (ativar) {
            getString(R.string.modo_resp_ativar_msg)
        } else {
            getString(R.string.modo_resp_desativar_msg)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.modo_resp_titulo))
            .setMessage(mensagem)
            .setPositiveButton(getString(R.string.sim)) { _, _ ->
                if (ativar) ativarModoResponsavel() else desativarModoResponsavel()
            }
            .setNegativeButton(getString(R.string.nao), null)
            .show()
    }

    private fun ativarModoResponsavel() {
        AppMode.isResponsavelFinanceiro = true
        reiniciarApp()
    }

    private fun desativarModoResponsavel() {
        AppMode.isResponsavelFinanceiro = false
        resetarAppEIrParaLogin()
    }

    /** Recarrega o app para que a MainActivity monte a UI de acordo com o novo modo. */
    private fun reiniciarApp() {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
        finish()
    }

    /** Apaga todos os dados do app e retorna para a tela de login. */
    private fun resetarAppEIrParaLogin() {
        clearAllCacheData()
        LoginActivity.getEncryptedPrefs(this).edit { clear() }
        getSharedPreferences(PermissionHelper.PREFS_NAME, MODE_PRIVATE).edit { clear() }
        getSharedPreferences("update_prompt_prefs", MODE_PRIVATE).edit { clear() }
        getSharedPreferences("UpdatePrefs", MODE_PRIVATE).edit { clear() }

        val intent = Intent(this, LoginActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
        finish()
    }

    // =================================================================

    private fun clearAllCacheData() {
        Dados.init(applicationContext)
        Dados.clearAllCacheFiles()
        getSharedPreferences("HomeFragmentCache", MODE_PRIVATE).edit { clear() }
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
            .setTitle(getString(R.string.settings_update_titulo, version))
            .setMessage(getString(R.string.settings_update_mensagem, releaseNotes))
            .setPositiveButton(getString(R.string.sim)) { _, _ -> startManualDownload(url) }
            .setNegativeButton(getString(R.string.nao), null)
            .show()
    }

    private fun startManualDownload(apkUrl: String) {
        coroutineScope.launch {
            val progressDialog = createProgressDialog().apply { show() }
            try {
                val apkFile = withContext(Dispatchers.IO) { downloadApk(apkUrl) }
                progressDialog.dismiss()
                apkFile?.let(::showInstallDialog)
                    ?: showError(getString(R.string.settings_update_erro_download))
            } catch (e: Exception) {
                progressDialog.dismiss()
                Log.e(tag, getString(R.string.settings_erro_download_log), e)
                showError(getString(R.string.settings_update_erro_download_msg, e.message ?: ""))
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
                if (!apkFile.exists()) { showError(getString(R.string.settings_apk_nao_encontrado)); return@runOnUiThread }

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
                        .setTitle(getString(R.string.settings_download_concluido))
                        .setMessage(getString(R.string.settings_instalar_msg))
                        .setPositiveButton(getString(R.string.instalar)) { _, _ -> startActivity(installIntent) }
                        .setNegativeButton(getString(R.string.cancelar), null)
                        .show()
                } else {
                    showError(getString(R.string.settings_nenhum_app_instalar))
                }
            } catch (e: Exception) {
                Log.e(tag, getString(R.string.settings_erro_instalacao), e)
                showError(getString(R.string.settings_erro_instalacao_msg, e.message ?: ""))
            }
        }
    }

    private fun showMessage() {
        MaterialAlertDialogBuilder(this)
            .setMessage(getString(R.string.settings_versao_atualizada))
            .setPositiveButton(getString(R.string.ok), null)
            .show()
    }

    private fun showError(msg: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.erro))
            .setMessage(msg)
            .setPositiveButton(getString(R.string.ok), null)
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        coroutineScope.cancel()
        progressBar = null
    }
}