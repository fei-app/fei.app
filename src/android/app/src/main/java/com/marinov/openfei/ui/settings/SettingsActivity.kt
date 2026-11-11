package com.marinov.openfei.ui.settings

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.webkit.CookieManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.marinov.openfei.R
import com.marinov.openfei.app.AppMode
import com.marinov.openfei.ui.login.LoginActivity
import com.marinov.openfei.ui.main.MainActivity
import com.marinov.openfei.util.PermissionHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import com.marinov.openfei.background.CalendarSyncManager
import com.marinov.openfei.background.SyncPrefs
import com.marinov.openfei.cache.CacheHelper
import com.marinov.openfei.data.DataInitializer
import com.marinov.openfei.update.AppUpdateDownloader
import com.marinov.openfei.update.UpdateChecker

class SettingsActivity : AppCompatActivity() {

    private val tag = "SettingsActivity"
    private val coroutineScope = CoroutineScope(Dispatchers.Main + Job())
    private lateinit var updateDownloader: AppUpdateDownloader
    private lateinit var switchModoResponsavel: MaterialSwitch
    private lateinit var switchExibirAgenda: MaterialSwitch
    private lateinit var spinnerFrequenciaMoodle: Spinner
    private lateinit var switchSomenteRedesIlimitadas: MaterialSwitch

    private val calendarPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val readGranted = result[Manifest.permission.READ_CALENDAR] == true
            val writeGranted = result[Manifest.permission.WRITE_CALENDAR] == true
            val granted = readGranted && writeGranted
            if (granted) {
                switchExibirAgenda.setOnCheckedChangeListener(null)
                switchExibirAgenda.isChecked = true
                switchExibirAgenda.setOnCheckedChangeListener(exibirAgendaListener)
                CalendarSyncManager.setEnabled(this, true)
                coroutineScope.launch {
                    CalendarSyncManager.syncIfNeeded(
                        context = this@SettingsActivity,
                        fetchOnline = true,
                        force = true
                    )
                }
                Toast.makeText(
                    this,
                    "Eventos adicionados à agenda do sistema",
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                switchExibirAgenda.setOnCheckedChangeListener(null)
                switchExibirAgenda.isChecked = false
                switchExibirAgenda.setOnCheckedChangeListener(exibirAgendaListener)
                CalendarSyncManager.setEnabled(this, false)
                Toast.makeText(
                    this,
                    "Permissão de calendário necessária para ativar este recurso",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

    private val modoResponsavelListener: android.widget.CompoundButton.OnCheckedChangeListener =
        android.widget.CompoundButton.OnCheckedChangeListener { switchView, isChecked ->
            switchView.setOnCheckedChangeListener(null)
            switchView.isChecked = !isChecked
            switchView.setOnCheckedChangeListener(modoResponsavelListener)
            confirmarMudancaModoResponsavel(isChecked)
        }

    private val exibirAgendaListener: android.widget.CompoundButton.OnCheckedChangeListener =
        android.widget.CompoundButton.OnCheckedChangeListener { switchView, isChecked ->
            switchView.setOnCheckedChangeListener(null)
            if (isChecked) {
                if (CalendarSyncManager.hasCalendarPermission(this@SettingsActivity)) {
                    CalendarSyncManager.setEnabled(this@SettingsActivity, true)
                    coroutineScope.launch {
                        CalendarSyncManager.syncIfNeeded(
                            context = this@SettingsActivity,
                            fetchOnline = true,
                            force = true
                        )
                    }
                    Toast.makeText(
                        this@SettingsActivity,
                        "Eventos adicionados à agenda do sistema",
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    calendarPermissionLauncher.launch(
                        arrayOf(
                            Manifest.permission.READ_CALENDAR,
                            Manifest.permission.WRITE_CALENDAR
                        )
                    )
                    switchView.isChecked = false
                }
            } else {
                CalendarSyncManager.setEnabled(this@SettingsActivity, false)
                coroutineScope.launch {
                    CalendarSyncManager.removeAll(this@SettingsActivity)
                }
                Toast.makeText(
                    this@SettingsActivity,
                    "Eventos removidos da agenda do sistema",
                    Toast.LENGTH_SHORT
                ).show()
            }
            switchView.setOnCheckedChangeListener(exibirAgendaListener)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        configureSystemBarsForLegacyDevices()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        DataInitializer.init(applicationContext)
        SyncPrefs.init(applicationContext)
        updateDownloader = AppUpdateDownloader(this, coroutineScope)
        setupToolbar()
        setupUI()
        setupSyncOptions()
        setupModoResponsavelFinanceiro()
        setupExibirEventosNaAgenda()
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
                    val currentNightMode =
                        resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
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
                        navigationBarColor =
                            ContextCompat.getColor(this@SettingsActivity, R.color.fundocartao)
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
        btnGitlab.setOnClickListener {
            openUrl("https://gitlab.com/fei.app/")
        }
        btnCheck.setOnClickListener {
            checkUpdate()
        }
        btnClear.setOnClickListener {
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
            clearAllCacheData()
            Toast.makeText(
                this,
                getString(R.string.settings_base_apagada),
                Toast.LENGTH_SHORT
            ).show()
        }
        btnClearPassword.setOnClickListener {
            clearAutoFill()
            Toast.makeText(
                this,
                getString(R.string.settings_autofill_apagado),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun setupSyncOptions() {
        setupFrequenciaAtualizacaoMoodle()
        setupSomenteRedesIlimitadas()
    }

    private fun setupFrequenciaAtualizacaoMoodle() {
        spinnerFrequenciaMoodle = findViewById(R.id.spinner_frequencia_moodle)
        val cardFrequenciaMoodle = findViewById<View>(R.id.card_frequencia_moodle)

        // No modo responsável financeiro, a frequência do Moodle não faz sentido
        if (AppMode.isResponsavelFinanceiro) {
            spinnerFrequenciaMoodle.isEnabled = false
            cardFrequenciaMoodle.alpha = 0.5f
            return
        }

        val valoresHoras = SyncPrefs.ALLOWED_MOODLE_FREQUENCIES // [1, 2, 3, 6]
        val rotulos = valoresHoras.map { h ->
            if (h == 1) getString(R.string.frequencia_1_hora)
            else getString(R.string.frequencia_x_horas, h)
        }
        spinnerFrequenciaMoodle.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            rotulos
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        val freqAtual = SyncPrefs.moodleFrequencyHours
        val index = valoresHoras.indexOf(freqAtual).coerceAtLeast(0)
        spinnerFrequenciaMoodle.setSelection(index)
        spinnerFrequenciaMoodle.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long
                ) {
                    val novoValor = valoresHoras[position]
                    if (SyncPrefs.moodleFrequencyHours != novoValor) {
                        SyncPrefs.moodleFrequencyHours = novoValor
                    }
                }

                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
    }

    private fun setupSomenteRedesIlimitadas() {
        switchSomenteRedesIlimitadas = findViewById(R.id.switch_somente_redes_ilimitadas)
        // Esta opção permanece ativa mesmo no modo responsável financeiro
        switchSomenteRedesIlimitadas.isChecked = SyncPrefs.syncOnlyOnUnmeteredNetworks
        switchSomenteRedesIlimitadas.setOnCheckedChangeListener { _, isChecked ->
            SyncPrefs.syncOnlyOnUnmeteredNetworks = isChecked
        }
    }

    private fun setupModoResponsavelFinanceiro() {
        switchModoResponsavel = findViewById(R.id.switch_modo_responsavel)
        switchModoResponsavel.isChecked = AppMode.isResponsavelFinanceiro
        switchModoResponsavel.setOnCheckedChangeListener(modoResponsavelListener)
    }

    private fun setupExibirEventosNaAgenda() {
        switchExibirAgenda = findViewById(R.id.switch_exibir_agenda)
        val cardExibirAgenda = findViewById<View>(R.id.card_exibir_agenda)
        if (AppMode.isResponsavelFinanceiro) {
            switchExibirAgenda.isEnabled = false
            switchExibirAgenda.isChecked = false
            cardExibirAgenda.alpha = 0.5f
        } else {
            switchExibirAgenda.isEnabled = true
            cardExibirAgenda.alpha = 1.0f
            val enabledInPrefs = CalendarSyncManager.isEnabled(this)
            val hasPermission = CalendarSyncManager.hasCalendarPermission(this)
            if (enabledInPrefs && !hasPermission) {
                CalendarSyncManager.setEnabled(this, false)
            }
            switchExibirAgenda.isChecked = enabledInPrefs && hasPermission
            switchExibirAgenda.setOnCheckedChangeListener(exibirAgendaListener)
        }
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

    private fun reiniciarApp() {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
        finish()
    }

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

    private fun clearAllCacheData() {
        DataInitializer.init(applicationContext)
        CacheHelper.clearAllCacheFiles()
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
            com.bumptech.glide.Glide.get(context).clearDiskCache()
        }
        com.bumptech.glide.Glide.get(context).clearMemory()
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
        UpdateChecker.checkForUpdate(
            this,
            true,
            object : UpdateChecker.UpdateListener {
                override fun onUpdateAvailable(
                    url: String,
                    version: String,
                    releaseNotes: String
                ) {
                    runOnUiThread {
                        promptForUpdate(url, version, releaseNotes)
                    }
                }

                override fun onUpToDate() {
                    runOnUiThread {
                        showMessage()
                    }
                }

                override fun onError(message: String) {
                    runOnUiThread {
                        showError(message)
                    }
                }
            }
        )
    }

    private fun promptForUpdate(
        url: String,
        version: String,
        releaseNotes: String
    ) {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.settings_update_titulo, version))
            .setMessage(getString(R.string.settings_update_mensagem, releaseNotes))
            .setPositiveButton(getString(R.string.sim)) { _, _ ->
                startManualDownload(url)
            }
            .setNegativeButton(getString(R.string.nao), null)
            .show()
    }

    private fun startManualDownload(apkUrl: String) {
        updateDownloader.startDownload(apkUrl, forced = false)
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
        if (::updateDownloader.isInitialized) {
            updateDownloader.destroy()
        }
    }
}