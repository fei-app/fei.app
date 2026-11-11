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
import androidx.core.view.updatePadding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.marinov.openfei.R
import com.marinov.openfei.app.AppMode
import com.marinov.openfei.background.CalendarSyncManager
import com.marinov.openfei.background.SyncPrefs
import com.marinov.openfei.background.SyncScheduler
import com.marinov.openfei.data.DataInitializer
import com.marinov.openfei.login.CredentialsStore
import com.marinov.openfei.login.SessionManager
import com.marinov.openfei.ui.login.LoginActivity
import com.marinov.openfei.ui.main.MainActivity
import com.marinov.openfei.update.AppUpdateDownloader
import com.marinov.openfei.update.UpdateChecker
import com.marinov.openfei.util.PermissionHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
            val granted = result[Manifest.permission.READ_CALENDAR] == true &&
                    result[Manifest.permission.WRITE_CALENDAR] == true

            switchExibirAgenda.setOnCheckedChangeListener(null)
            switchExibirAgenda.isChecked = granted
            switchExibirAgenda.setOnCheckedChangeListener(exibirAgendaListener)

            CalendarSyncManager.setEnabled(this, granted)

            if (granted) {
                coroutineScope.launch {
                    CalendarSyncManager.syncIfNeeded(this@SettingsActivity, fetchOnline = true, force = true)
                }
                Toast.makeText(this, getString(R.string.settings_agenda_ativada), Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(
                    this, getString(R.string.settings_agenda_permissao_necessaria), Toast.LENGTH_SHORT
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
                if (CalendarSyncManager.hasCalendarPermission(this)) {
                    CalendarSyncManager.setEnabled(this, true)
                    coroutineScope.launch {
                        CalendarSyncManager.syncIfNeeded(
                            this@SettingsActivity, fetchOnline = true, force = true
                        )
                    }
                    Toast.makeText(
                        this, getString(R.string.settings_agenda_ativada), Toast.LENGTH_SHORT
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
                CalendarSyncManager.setEnabled(this, false)
                coroutineScope.launch { CalendarSyncManager.removeAll(this@SettingsActivity) }
                Toast.makeText(
                    this, getString(R.string.settings_agenda_removida), Toast.LENGTH_SHORT
                ).show()
            }

            switchView.setOnCheckedChangeListener(exibirAgendaListener)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        configureSystemBarsForLegacyDevices()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        DataInitializer.init(applicationContext)
        updateDownloader = AppUpdateDownloader(this, coroutineScope)

        setupToolbar()
        setupUI()
        setupSyncOptions()
        setupModoResponsavelFinanceiro()
        setupExibirEventosNaAgenda()

        if (intent.getBooleanExtra("open_update_directly", false)) checkUpdate()
    }

    private fun setupToolbar() {
        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.toolbar)) { v, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            v.updatePadding(top = top)
            insets
        }

        // ★ BUG 20: edge-to-edge (target 35+) — conteúdo não fica sob a barra de gestos.
        val scroll = findViewById<View>(R.id.settings_scroll)
        if (scroll != null) {
            val initialBottom = scroll.paddingBottom
            ViewCompat.setOnApplyWindowInsetsListener(scroll) { v, insets ->
                val bottom = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
                v.updatePadding(bottom = initialBottom + bottom)
                insets
            }
        }
    }

    private fun setupUI() {
        findViewById<Button>(R.id.btn_gitlab).setOnClickListener {
            openUrl("https://gitlab.com/fei.app/")
        }
        findViewById<Button>(R.id.btn_check_update).setOnClickListener { checkUpdate() }

        findViewById<Button>(R.id.btn_clear_data).setOnClickListener {
            coroutineScope.launch {
                SessionManager.invalidateLocalSession(clearData = true)
                CredentialsStore.setLoggedIn(this@SettingsActivity, true) // mantém login
                clearWebViewData()
                // ★ BUG 34: "Limpar dados" também remove os eventos da agenda do sistema
                CalendarSyncManager.removeAll(this@SettingsActivity)
                Toast.makeText(
                    this@SettingsActivity,
                    getString(R.string.settings_base_apagada),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        findViewById<Button>(R.id.btn_clear_password).setOnClickListener {
            coroutineScope.launch { clearAutoFill() }
        }
    }

    private fun setupSyncOptions() {
        setupFrequenciaAtualizacaoMoodle()
        setupSomenteRedesIlimitadas()
    }

    private fun setupFrequenciaAtualizacaoMoodle() {
        spinnerFrequenciaMoodle = findViewById(R.id.spinner_frequencia_moodle)
        val cardFrequenciaMoodle = findViewById<View>(R.id.card_frequencia_moodle)

        if (AppMode.isResponsavelFinanceiro) {
            spinnerFrequenciaMoodle.isEnabled = false
            cardFrequenciaMoodle.alpha = 0.5f
            return
        }

        val valoresHoras = SyncPrefs.ALLOWED_MOODLE_FREQUENCIES
        val rotulos = valoresHoras.map { h ->
            if (h == 1) getString(R.string.frequencia_1_hora)
            else getString(R.string.frequencia_x_horas, h)
        }

        spinnerFrequenciaMoodle.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_item, rotulos
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

        val index = valoresHoras.indexOf(SyncPrefs.moodleFrequencyHours).coerceAtLeast(0)
        spinnerFrequenciaMoodle.setSelection(index)

        spinnerFrequenciaMoodle.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: AdapterView<*>?, view: View?, position: Int, id: Long
                ) {
                    val novoValor = valoresHoras[position]
                    if (SyncPrefs.moodleFrequencyHours != novoValor) {
                        SyncPrefs.moodleFrequencyHours = novoValor
                        // ★ BUG 36: reagenda na hora (antes só valia no próximo ciclo)
                        SyncScheduler.rescheduleMoodle(this@SettingsActivity)
                    }
                }

                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
    }

    private fun setupSomenteRedesIlimitadas() {
        switchSomenteRedesIlimitadas = findViewById(R.id.switch_somente_redes_ilimitadas)
        switchSomenteRedesIlimitadas.isChecked = SyncPrefs.syncOnlyOnUnmeteredNetworks
        switchSomenteRedesIlimitadas.setOnCheckedChangeListener { _, isChecked ->
            SyncPrefs.syncOnlyOnUnmeteredNetworks = isChecked
            SyncScheduler.scheduleAll(this)
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

        // ★ BUG 57: no modo responsável o recurso é desligado DE VERDADE (prefs + agenda).
        if (AppMode.isResponsavelFinanceiro) {
            switchExibirAgenda.setOnCheckedChangeListener(null)
            switchExibirAgenda.isEnabled = false
            switchExibirAgenda.isChecked = false
            cardExibirAgenda.alpha = 0.5f
            if (CalendarSyncManager.isEnabled(this)) {
                CalendarSyncManager.setEnabled(this, false)
                coroutineScope.launch { CalendarSyncManager.removeAll(this@SettingsActivity) }
            }
            return
        }

        switchExibirAgenda.isEnabled = true
        cardExibirAgenda.alpha = 1.0f

        val enabledInPrefs = CalendarSyncManager.isEnabled(this)
        val hasPermission = CalendarSyncManager.hasCalendarPermission(this)
        if (enabledInPrefs && !hasPermission) CalendarSyncManager.setEnabled(this, false)

        switchExibirAgenda.isChecked = enabledInPrefs && hasPermission
        switchExibirAgenda.setOnCheckedChangeListener(exibirAgendaListener)
    }

    private fun confirmarMudancaModoResponsavel(ativar: Boolean) {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.modo_resp_titulo))
            .setMessage(
                getString(
                    if (ativar) R.string.modo_resp_ativar_msg else R.string.modo_resp_desativar_msg
                )
            )
            .setPositiveButton(getString(R.string.sim)) { _, _ ->
                if (ativar) ativarModoResponsavel() else desativarModoResponsavel()
            }
            .setNegativeButton(getString(R.string.nao), null)
            .show()
    }

    private fun ativarModoResponsavel() {
        AppMode.isResponsavelFinanceiro = true
        // ★ BUG 57: agenda do sistema é desligada junto com o modo responsável
        if (CalendarSyncManager.isEnabled(this)) {
            CalendarSyncManager.setEnabled(this, false)
            coroutineScope.launch {
                CalendarSyncManager.removeAll(this@SettingsActivity)
                SyncScheduler.scheduleAll(this@SettingsActivity)
                reiniciarApp()
            }
        } else {
            SyncScheduler.scheduleAll(this)
            reiniciarApp()
        }
    }

    private fun desativarModoResponsavel() {
        AppMode.isResponsavelFinanceiro = false
        SyncScheduler.scheduleAll(this)
        coroutineScope.launch {
            // ★ BUG 5: troca de modo invalida sessão + cache (evita cruzar dados)
            SessionManager.invalidateLocalSession(clearData = true)
            CredentialsStore.wipe(this@SettingsActivity)
            getSharedPreferences(PermissionHelper.PREFS_NAME, MODE_PRIVATE).edit { clear() }
            getSharedPreferences("update_prompt_prefs", MODE_PRIVATE).edit { clear() }
            getSharedPreferences("UpdatePrefs", MODE_PRIVATE).edit { clear() }
            irParaLogin()
        }
    }

    private fun reiniciarApp() {
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
        )
        finish()
    }

    private fun irParaLogin() {
        startActivity(
            Intent(this, LoginActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
        )
        finish()
    }

    /**
     * ★ BUG 7: não usamos mais SharedPreferences PLANO sobre o arquivo criptografado
     * (isso destruía os keysets do Tink e podia crashar o app no próximo login).
     */
    private suspend fun clearAutoFill() {
        SessionManager.invalidateLocalSession(clearData = true)
        CredentialsStore.clearCredentials(this)
        clearWebViewData()

        withContext(Dispatchers.Main) {
            Toast.makeText(
                this@SettingsActivity,
                getString(R.string.settings_autofill_apagado),
                Toast.LENGTH_SHORT
            ).show()
            irParaLogin()
        }
    }

    private fun clearWebViewData() {
        runCatching {
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
            android.webkit.WebStorage.getInstance().deleteAllData()
            android.webkit.WebView.clearClientCertPreferences(null)
        }
        runCatching {
            val context = applicationContext
            android.webkit.WebView(context).apply {
                clearCache(true)
                clearHistory()
                clearFormData()
                destroy()
            }
        }
        runCatching { com.bumptech.glide.Glide.get(applicationContext).clearMemory() }
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { com.bumptech.glide.Glide.get(applicationContext).clearDiskCache() }
        }
        getSharedPreferences("HomeFragmentCache", MODE_PRIVATE).edit { clear() }
    }

    private fun openUrl(url: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) }
            .onFailure { Log.e(tag, "Erro ao abrir URL", it) }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun checkUpdate() {
        UpdateChecker.checkForUpdate(
            this, true,
            object : UpdateChecker.UpdateListener {
                override fun onUpdateAvailable(url: String, version: String, releaseNotes: String) {
                    runOnUiThread { promptForUpdate(url, version, releaseNotes) }
                }

                override fun onUpToDate() {
                    runOnUiThread { showMessage() }
                }

                override fun onError(message: String) {
                    runOnUiThread { showError(message) }
                }
            }
        )
    }

    private fun promptForUpdate(url: String, version: String, releaseNotes: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.settings_update_titulo, version))
            .setMessage(getString(R.string.settings_update_mensagem, releaseNotes))
            .setPositiveButton(getString(R.string.sim)) { _, _ ->
                updateDownloader.startDownload(url, forced = false)
            }
            .setNegativeButton(getString(R.string.nao), null)
            .show()
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
        if (::updateDownloader.isInitialized) updateDownloader.destroy()
    }

    @SuppressLint("ObsoleteSdkInt")
    private fun configureSystemBarsForLegacyDevices() {
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) return

        val isDarkMode = when (AppCompatDelegate.getDefaultNightMode()) {
            AppCompatDelegate.MODE_NIGHT_YES -> true
            AppCompatDelegate.MODE_NIGHT_NO -> false
            else -> (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                    Configuration.UI_MODE_NIGHT_YES
        }

        window.apply {
            @Suppress("DEPRECATION")
            clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS)
            addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)

            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.N_MR1) {
                @Suppress("DEPRECATION") statusBarColor = Color.BLACK
                @Suppress("DEPRECATION") navigationBarColor = Color.BLACK
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    @Suppress("DEPRECATION")
                    decorView.systemUiVisibility =
                        decorView.systemUiVisibility and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
                }
            } else {
                @Suppress("DEPRECATION")
                navigationBarColor =
                    ContextCompat.getColor(this@SettingsActivity, R.color.fundocartao)
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            @Suppress("DEPRECATION")
            var flags = window.decorView.systemUiVisibility
            @Suppress("DEPRECATION")
            flags = if (isDarkMode) flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
            else if (Build.VERSION.SDK_INT > Build.VERSION_CODES.N_MR1)
                flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            else flags
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = flags
        }

        if (!isDarkMode && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                window.decorView.systemUiVisibility or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }
    }
}