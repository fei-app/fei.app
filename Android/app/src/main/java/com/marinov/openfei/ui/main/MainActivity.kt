package com.marinov.openfei.ui.main

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.widget.ProgressBar
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.content.edit
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.lifecycleScope
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.behavior.HideBottomViewOnScrollBehavior
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.navigationrail.NavigationRailView
import com.marinov.openfei.BuildConfig
import com.marinov.openfei.app.AppMode
import com.marinov.openfei.data.Dados
import com.marinov.openfei.data.NetworkChecker
import com.marinov.openfei.data.SessionManager
import com.marinov.openfei.data.UpdateChecker
import com.marinov.openfei.service.BackgroundService
import com.marinov.openfei.ui.boletos.BoletosFragment
import com.marinov.openfei.ui.home.HomeFragment
import com.marinov.openfei.ui.horarios.HorariosAula
import com.marinov.openfei.ui.login.LoginActivity
import com.marinov.openfei.ui.moodle.MoodleFragment
import com.marinov.openfei.ui.more.MoreFragment
import com.marinov.openfei.ui.notas.NotasFragment
import com.marinov.openfei.ui.profile.ProfileFragment
import com.marinov.openfei.ui.provas.CalendarioProvas
import com.marinov.openfei.ui.settings.SettingsActivity
import com.marinov.openfei.util.WebViewHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

@Suppress("ANNOTATIONS_ON_BLOCK_LEVEL_EXPRESSION_ON_THE_SAME_LINE")
class MainActivity : AppCompatActivity() {
    interface RefreshableFragment {
        fun onRefresh()
    }

    companion object {
        private const val TAG = "MainActivity"
        private const val KEY_CURRENT_FRAGMENT_ID = "current_fragment_id"
        private const val REQUEST_NOTIFICATION_PERMISSION = 101
        private const val UPDATE_PROMPT_PREFS = "update_prompt_prefs"
        private const val KEY_UPDATE_SKIP_COUNT = "update_skip_count"
        private const val KEY_LAST_SKIPPED_VERSION = "last_skipped_version"
        private const val MAX_UPDATE_SKIPS = 3
        private val REFRESHABLE_FRAGMENTS = setOf(
            com.marinov.openfei.R.id.navigation_notas,
            com.marinov.openfei.R.id.action_profile,
            com.marinov.openfei.R.id.option_boletos
        )
        const val STATUS_OFFLINE = "0"
        const val STATUS_ONLINE_OK = "1"
        const val STATUS_LOGIN_NEEDED = "A"
    }

    private var currentFragment: Fragment? = null
    private lateinit var bottomNav: BottomNavigationView
    private lateinit var bottomNavContainer: View
    private lateinit var navRail: NavigationRailView
    private lateinit var swipeRefreshLayout: SwipeRefreshLayout
    private var isLayoutReady = false
    private var currentFragmentId = View.NO_ID
    private var isUpdatingSelection = false
    private var isKeypadListenerAdded = false
    private val updateScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var updateProgressBar: ProgressBar? = null
    private var isDownloadingUpdate = false
    private var hasCheckedUpdateOnOpen = false

    private val isModoResponsavel: Boolean
        get() = AppMode.isResponsavelFinanceiro

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!isUserLoggedIn()) {
            launchLogin()
            return
        }
        Dados.init(applicationContext)
        WebViewHelper.ensureWebView(applicationContext)
        configureSystemBarsForLegacyDevices()
        MaterialColors.getColor(
            this,
            com.google.android.material.R.attr.colorPrimaryContainer,
            Color.BLACK
        )
        setContentView(com.marinov.openfei.R.layout.activity_main)

        if (savedInstanceState != null) {
            currentFragmentId = savedInstanceState.getInt(KEY_CURRENT_FRAGMENT_ID, View.NO_ID)
            currentFragment =
                supportFragmentManager.findFragmentById(com.marinov.openfei.R.id.nav_host_fragment)
        }

        swipeRefreshLayout = findViewById(com.marinov.openfei.R.id.swipeRefreshLayout)
        swipeRefreshLayout.setOnRefreshListener {
            if (isRefreshEnabled()) {
                (currentFragment as? RefreshableFragment)?.onRefresh() ?: run {
                    swipeRefreshLayout.isRefreshing = false
                }
            } else {
                swipeRefreshLayout.isRefreshing = false
            }
        }
        swipeRefreshLayout.setDistanceToTriggerSync(250)

        val toolbar: MaterialToolbar = findViewById(com.marinov.openfei.R.id.topAppBar)
        setSupportActionBar(toolbar)
        ViewCompat.setOnApplyWindowInsetsListener(toolbar) { v, insets ->
            val statusBarHeight = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            v.setPadding(v.paddingLeft, statusBarHeight, v.paddingRight, v.paddingBottom)
            insets
        }

        bottomNav = findViewById(com.marinov.openfei.R.id.bottom_navigation)
        bottomNavContainer = findViewById(com.marinov.openfei.R.id.bottom_nav_container)
        navRail = findViewById(com.marinov.openfei.R.id.navigation_rail)

        ViewCompat.setOnApplyWindowInsetsListener(bottomNavContainer) { v, insets ->
            val systemBarsInsets = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val layoutParams = v.layoutParams as ViewGroup.MarginLayoutParams
            val originalMarginBottom = (20 * resources.displayMetrics.density).toInt()
            layoutParams.bottomMargin = originalMarginBottom + systemBarsInsets.bottom
            v.layoutParams = layoutParams
            insets
        }

        val rootView = findViewById<View>(com.marinov.openfei.R.id.main)
        rootView.viewTreeObserver.addOnGlobalLayoutListener(object :
            ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                if (isLayoutReady) return
                rootView.viewTreeObserver.removeOnGlobalLayoutListener(this)
                isLayoutReady = true
                configureNavigationForDevice()
                handleIntent(intent)
            }
        })

        BackgroundService.start(this)

        if (savedInstanceState == null) {
            navigateToHome()
            checkForUpdateOnOpen()
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                try {
                    ActivityCompat.requestPermissions(
                        this,
                        arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                        REQUEST_NOTIFICATION_PERMISSION
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Erro ao solicitar permissão de notificação", e)
                }
            }
        }
    }

    private fun isUserLoggedIn(): Boolean {
        return try {
            val prefs = LoginActivity.getEncryptedPrefs(this)
            prefs.getBoolean(LoginActivity.KEY_IS_LOGGED_IN, false)
        } catch (_: Exception) {
            false
        }
    }

    suspend fun isOnline(): Boolean {
        return NetworkChecker.isOnline()
    }

    suspend fun checkConnectionAndSession(): String {
        val status = SessionManager.checkConnectionAndSession()

        if (status == STATUS_LOGIN_NEEDED) {
            withContext(Dispatchers.Main) {
                launchLogin()
            }
        }

        return status
    }

    private fun launchLogin() {
        try {
            LoginActivity.getEncryptedPrefs(this).edit {
                putBoolean(LoginActivity.KEY_IS_LOGGED_IN, false)
            }
        } catch (_: Exception) {
        }
        startActivity(
            Intent(this, LoginActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
        )
        finish()
    }

    override fun onResume() {
        super.onResume()
        requestNotificationPermissionIfNeeded()
        if (isLayoutReady) {
            configureNavigationForDevice()
            invalidateOptionsMenu()
        }
        lifecycleScope.launch { checkConnectionAndSession() }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_CURRENT_FRAGMENT_ID, currentFragmentId)
    }

    private fun isRefreshEnabled(): Boolean {
        return when {
            currentFragmentId == View.NO_ID -> currentFragment is RefreshableFragment
            else -> REFRESHABLE_FRAGMENTS.contains(currentFragmentId)
        }
    }

    private fun updateRefreshLayoutState() {
        swipeRefreshLayout.isEnabled = isRefreshEnabled()
        if (!isRefreshEnabled()) swipeRefreshLayout.isRefreshing = false
    }

    fun setRefreshing(refreshing: Boolean) {
        swipeRefreshLayout.isRefreshing = refreshing && isRefreshEnabled()
    }

    fun showBottomNavigation() {
        if (::bottomNavContainer.isInitialized && bottomNavContainer.isVisible) {
            val layoutParams = bottomNavContainer.layoutParams as? CoordinatorLayout.LayoutParams
            @Suppress("UNCHECKED_CAST")
            val behavior = layoutParams?.behavior as? HideBottomViewOnScrollBehavior<View>
            behavior?.slideUp(bottomNavContainer)
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (isLayoutReady) handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val destination = intent?.getStringExtra("destination") ?: run {
            val hasRestoredFragment =
                supportFragmentManager.findFragmentById(com.marinov.openfei.R.id.nav_host_fragment) != null
            if (!hasRestoredFragment) navigateToHome()
            return
        }
        if (isModoResponsavel && destination != "boletos") return
        when (destination) {
            "notas" -> openFragment(com.marinov.openfei.R.id.navigation_notas)
            "horarios" -> openFragment(com.marinov.openfei.R.id.option_horarios_aula)
            "provas" -> openFragment(com.marinov.openfei.R.id.option_calendario_provas)
            "boletos" -> openFragment(com.marinov.openfei.R.id.option_boletos)
        }
    }

    fun openFragment(fragmentId: Int) {
        if (isFinishing || isDestroyed) return
        swipeRefreshLayout.isRefreshing = false
        val fragment = when (fragmentId) {
            com.marinov.openfei.R.id.navigation_home -> HomeFragment()
            com.marinov.openfei.R.id.navigation_moodle -> MoodleFragment()
            com.marinov.openfei.R.id.option_calendario_provas -> CalendarioProvas()
            com.marinov.openfei.R.id.navigation_notas -> NotasFragment()
            com.marinov.openfei.R.id.option_horarios_aula -> HorariosAula()
            com.marinov.openfei.R.id.action_profile -> ProfileFragment()
            com.marinov.openfei.R.id.navigation_more -> MoreFragment()
            com.marinov.openfei.R.id.option_boletos -> BoletosFragment()
            else -> return
        }

        if (supportFragmentManager.backStackEntryCount > 0) {
            supportFragmentManager.popBackStackImmediate(null, FragmentManager.POP_BACK_STACK_INCLUSIVE)
        }

        currentFragment = fragment
        currentFragmentId = fragmentId
        supportFragmentManager.beginTransaction()
            .replace(com.marinov.openfei.R.id.nav_host_fragment, fragment)
            .commit()
        updateMenuSelection(fragmentId)
        updateRefreshLayoutState()
        showBottomNavigation()
    }

    private fun updateMenuSelection(fragmentId: Int) {
        if (isUpdatingSelection) return
        if (isModoResponsavel) return
        isUpdatingSelection = true
        runOnUiThread {
            try {
                if (resources.getBoolean(com.marinov.openfei.R.bool.isTablet)) {
                    if (navRail.selectedItemId != fragmentId) navRail.selectedItemId = fragmentId
                } else {
                    if (bottomNav.selectedItemId != fragmentId) bottomNav.selectedItemId =
                        fragmentId
                }
            } finally {
                isUpdatingSelection = false
            }
        }
    }

    private fun configureNavigationForDevice() {
        if (isModoResponsavel) {
            navRail.visibility = View.GONE
            bottomNavContainer.visibility = View.GONE
            return
        }
        val isTablet = resources.getBoolean(com.marinov.openfei.R.bool.isTablet)
        if (isTablet) {
            bottomNavContainer.visibility = View.GONE
            navRail.visibility = View.VISIBLE
            navRail.setOnItemSelectedListener { item ->
                if (!isUpdatingSelection) openFragment(item.itemId)
                true
            }
        } else {
            navRail.visibility = View.GONE
            bottomNavContainer.visibility = View.VISIBLE
            bottomNav.setOnItemSelectedListener { item ->
                if (!isUpdatingSelection) openFragment(item.itemId)
                true
            }
            if (!isKeypadListenerAdded) {
                val rootView: View = findViewById(com.marinov.openfei.R.id.main)
                rootView.viewTreeObserver.addOnGlobalLayoutListener {
                    val r = Rect()
                    rootView.getWindowVisibleDisplayFrame(r)
                    val screenHeight = rootView.rootView.height
                    val keypadHeight = screenHeight - r.bottom
                    bottomNavContainer.visibility =
                        if (keypadHeight > screenHeight * 0.15) View.GONE else View.VISIBLE
                }
                isKeypadListenerAdded = true
            }
        }
    }

    fun openCustomFragment(fragment: Fragment) {
        swipeRefreshLayout.isRefreshing = false
        currentFragment = fragment
        currentFragmentId = View.NO_ID
        supportFragmentManager.beginTransaction()
            .setCustomAnimations(android.R.anim.fade_in, android.R.anim.fade_out)
            .replace(com.marinov.openfei.R.id.nav_host_fragment, fragment)
            .addToBackStack(null)
            .commit()
        updateMenuSelection(View.NO_ID)
        updateRefreshLayoutState()
        showBottomNavigation()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(com.marinov.openfei.R.menu.top_app_bar_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            com.marinov.openfei.R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            com.marinov.openfei.R.id.action_profile -> {
                openFragment(com.marinov.openfei.R.id.action_profile)
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    fun navigateToHome() {
        if (isModoResponsavel) {
            openFragment(com.marinov.openfei.R.id.option_boletos)
        } else {
            openFragment(com.marinov.openfei.R.id.navigation_home)
        }
    }

    private fun checkForUpdateOnOpen() {
        if (hasCheckedUpdateOnOpen || isFinishing || isDestroyed) return
        hasCheckedUpdateOnOpen = true

        lifecycleScope.launch {
            // Verifica se está online antes de checar atualizações
            if (!isOnline()) return@launch

            UpdateChecker.checkForUpdate(
                this@MainActivity,
                true,
                object : UpdateChecker.UpdateListener {
                    override fun onUpdateAvailable(
                        url: String,
                        version: String,
                        releaseNotes: String
                    ) {
                        runOnUiThread {
                            if (!isFinishing && !isDestroyed) {
                                showUpdatePrePrompt(url, version, releaseNotes)
                            }
                        }
                    }
                    override fun onUpToDate() {
                        runOnUiThread {
                            if (!isFinishing && !isDestroyed) {
                                resetUpdateSkipCount()
                            }
                        }
                    }
                    override fun onError(message: String) {
                        Log.w(TAG, "UpdateChecker onError: $message")
                    }
                }
            )
        }
    }

    private fun showUpdatePrePrompt(
        apkUrl: String,
        version: String,
        releaseNotes: String
    ) {
        if (isFinishing || isDestroyed) return
        val skipCount = getUpdateSkipCount(version)
        val forced = skipCount >= MAX_UPDATE_SKIPS
        val builder = MaterialAlertDialogBuilder(this)
        builder.setTitle(getString(com.marinov.openfei.R.string.update_available_title))
            .setMessage(getString(com.marinov.openfei.R.string.update_available_message))
            .setCancelable(false)
            .setPositiveButton(getString(com.marinov.openfei.R.string.sim)) { _, _ ->
                showUpdateReleasePrompt(apkUrl, version, releaseNotes, forced)
            }
        if (forced) {
            builder.setNegativeButton(getString(com.marinov.openfei.R.string.nao), null)
        } else {
            builder.setNegativeButton(getString(com.marinov.openfei.R.string.nao)) { _, _ ->
                registerUpdateSkip(version)
            }
        }
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
    }

    private fun showUpdateReleasePrompt(
        apkUrl: String,
        version: String,
        releaseNotes: String,
        forced: Boolean
    ) {
        if (isFinishing || isDestroyed) return
        val builder = MaterialAlertDialogBuilder(this)
        builder.setTitle(getString(com.marinov.openfei.R.string.settings_update_titulo, version))
            .setMessage(
                getString(
                    com.marinov.openfei.R.string.settings_update_mensagem,
                    releaseNotes
                )
            )
            .setCancelable(false)
            .setPositiveButton(getString(com.marinov.openfei.R.string.sim)) { _, _ ->
                startManualDownload(apkUrl, forced)
            }
            .setNegativeButton(getString(com.marinov.openfei.R.string.nao), null)
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
    }

    private fun getUpdateSkipCount(latestVersion: String): Int {
        val prefs = getSharedPreferences(UPDATE_PROMPT_PREFS, MODE_PRIVATE)
        val skippedVersion = prefs.getString(KEY_LAST_SKIPPED_VERSION, "")
        val currentVersion = BuildConfig.VERSION_NAME
        if (skippedVersion.isNullOrEmpty()) return 0
        if (skippedVersion != latestVersion) return 0
        if (UpdateChecker.isVersionGreater(currentVersion, skippedVersion)) return 0
        return prefs.getInt(KEY_UPDATE_SKIP_COUNT, 0)
    }

    private fun registerUpdateSkip(version: String) {
        val prefs = getSharedPreferences(UPDATE_PROMPT_PREFS, MODE_PRIVATE)
        val currentCount = getUpdateSkipCount(version)
        if (currentCount >= MAX_UPDATE_SKIPS) return
        prefs.edit {
            putInt(KEY_UPDATE_SKIP_COUNT, currentCount + 1)
            putString(KEY_LAST_SKIPPED_VERSION, version)
        }
    }

    private fun resetUpdateSkipCount() {
        getSharedPreferences(UPDATE_PROMPT_PREFS, MODE_PRIVATE).edit {
            putInt(KEY_UPDATE_SKIP_COUNT, 0)
            putString(KEY_LAST_SKIPPED_VERSION, "")
        }
    }

    private fun startManualDownload(apkUrl: String, forced: Boolean) {
        if (isDownloadingUpdate || isFinishing || isDestroyed) return
        isDownloadingUpdate = true
        val progressDialog = createProgressDialog().apply { show() }
        updateScope.launch {
            try {
                val apkFile = withContext(Dispatchers.IO) { downloadApk(apkUrl) }
                if (!isFinishing && !isDestroyed) {
                    progressDialog.dismiss()
                }
                isDownloadingUpdate = false
                if (apkFile != null) {
                    showInstallDialog(apkFile, forced)
                } else {
                    showErrorUpdateDialog(
                        getString(com.marinov.openfei.R.string.settings_update_erro_download)
                    )
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!isFinishing && !isDestroyed) {
                    progressDialog.dismiss()
                }
                isDownloadingUpdate = false
                Log.e(TAG, getString(com.marinov.openfei.R.string.settings_erro_download_log), e)
                showErrorUpdateDialog(
                    getString(
                        com.marinov.openfei.R.string.settings_update_erro_download_msg,
                        e.message ?: ""
                    )
                )
            }
        }
    }

    @SuppressLint("InflateParams")
    private fun createProgressDialog(): AlertDialog {
        val view = layoutInflater.inflate(
            com.marinov.openfei.R.layout.dialog_download_progress,
            null
        )
        updateProgressBar = view.findViewById(com.marinov.openfei.R.id.progress_bar)
        return MaterialAlertDialogBuilder(this)
            .setView(view)
            .setCancelable(false)
            .create()
    }

    private suspend fun downloadApk(apkUrl: String): File? = withContext(Dispatchers.IO) {
        try {
            val connection = URL(apkUrl).openConnection() as HttpURLConnection
            connection.connectTimeout = 15000
            connection.readTimeout = 15000
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
                            withContext(Dispatchers.Main) {
                                updateProgressBar?.progress = progress
                            }
                        }
                    }
                }
            }
            outputFile
        } catch (e: Exception) {
            Log.e(TAG, "Erro no download.", e)
            null
        }
    }

    private fun showInstallDialog(apkFile: File, forced: Boolean) {
        if (isFinishing || isDestroyed) return
        try {
            if (!apkFile.exists()) {
                showErrorUpdateDialog(
                    getString(com.marinov.openfei.R.string.settings_apk_nao_encontrado)
                )
                return
            }
            val apkUri = FileProvider.getUriForFile(
                this@MainActivity,
                "${BuildConfig.APPLICATION_ID}.fileprovider",
                apkFile
            )
            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (installIntent.resolveActivity(packageManager) != null) {
                val builder = MaterialAlertDialogBuilder(this@MainActivity)
                builder.setTitle(getString(com.marinov.openfei.R.string.settings_download_concluido))
                    .setMessage(getString(com.marinov.openfei.R.string.settings_instalar_msg))
                    .setCancelable(false)
                    .setPositiveButton(getString(com.marinov.openfei.R.string.instalar)) { _, _ ->
                        try {
                            startActivity(installIntent)
                        } catch (e: Exception) {
                            Log.e(
                                TAG,
                                getString(com.marinov.openfei.R.string.settings_erro_instalacao),
                                e
                            )
                            showErrorUpdateDialog(
                                getString(
                                    com.marinov.openfei.R.string.settings_erro_instalacao_msg,
                                    e.message ?: ""
                                )
                            )
                        }
                    }
                    .setNegativeButton(getString(com.marinov.openfei.R.string.cancelar), null)
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
                showErrorUpdateDialog(
                    getString(com.marinov.openfei.R.string.settings_nenhum_app_instalar)
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, getString(com.marinov.openfei.R.string.settings_erro_instalacao), e)
            showErrorUpdateDialog(
                getString(
                    com.marinov.openfei.R.string.settings_erro_instalacao_msg,
                    e.message ?: ""
                )
            )
        }
    }

    private fun showErrorUpdateDialog(message: String) {
        if (isFinishing || isDestroyed) return
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(com.marinov.openfei.R.string.erro))
            .setMessage(message)
            .setPositiveButton(getString(com.marinov.openfei.R.string.ok), null)
            .show()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
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
                    @Suppress("DEPRECATION") clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS)
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
                        navigationBarColor = if (isDarkMode) {
                            ContextCompat.getColor(this@MainActivity, com.marinov.openfei.R.color.nav_bar_dark)
                        } else {
                            ContextCompat.getColor(this@MainActivity, com.marinov.openfei.R.color.nav_bar_light)
                        }
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

    override fun onDestroy() {
        super.onDestroy()
        updateScope.cancel()
        updateProgressBar = null
    }
}