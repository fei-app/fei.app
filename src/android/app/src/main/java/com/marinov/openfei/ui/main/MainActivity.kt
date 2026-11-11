package com.marinov.openfei.ui.main

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Rect
import android.os.Build
import com.marinov.openfei.login.AuthState
import com.marinov.openfei.login.RustSession
import com.marinov.openfei.login.SessionResult
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowManager
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.behavior.HideBottomViewOnScrollBehavior
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.navigationrail.NavigationRailView
import com.marinov.openfei.BuildConfig
import com.marinov.openfei.R
import com.marinov.openfei.app.AppMode
import com.marinov.openfei.background.SyncScheduler
import com.marinov.openfei.data.DataInitializer
import com.marinov.openfei.login.CredentialsStore
import com.marinov.openfei.login.SessionManager
import com.marinov.openfei.network.NetworkChecker
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
import com.marinov.openfei.update.AppUpdateDownloader
import com.marinov.openfei.update.UpdateChecker
import com.marinov.openfei.util.PermissionHelper
import com.marinov.openfei.util.WebViewHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

        private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        private var loginAttempted = false

        private val REFRESHABLE_FRAGMENTS = setOf(
            R.id.navigation_notas,
            R.id.action_profile,
            R.id.option_boletos
        )

        // ★ BUG 78: removidas as constantes duplicadas.
        // Agora a UI usa SessionManager.STATUS_*.
    }

    private var currentFragment: Fragment? = null
    private lateinit var bottomNav: BottomNavigationView
    private lateinit var bottomNavContainer: View
    private lateinit var navRail: NavigationRailView
    private lateinit var swipeRefreshLayout: androidx.swiperefreshlayout.widget.SwipeRefreshLayout
    private lateinit var updateDownloader: AppUpdateDownloader

    private var isLayoutReady = false
    private var currentFragmentId = View.NO_ID
    private var isUpdatingSelection = false
    private var isKeypadListenerAdded = false
    private val updateScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var hasCheckedUpdateOnOpen = false

    private val isModoResponsavel: Boolean
        get() = AppMode.isResponsavelFinanceiro

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        updateDownloader = AppUpdateDownloader(this, updateScope)

        // ★ PROBLEMA 1: instalação limpa NÃO é "sessão expirada".
        if (!CredentialsStore.hasCredentials(this)) {
            launchLogin(fromSessionExpiry = false)
            return
        }
        if (!CredentialsStore.isLoggedIn(this) && !AuthState.isAuthBlocked(this)) {
            loginAttempted = true
            attemptSilentLogin()
            return
        }

        if (AuthState.isAuthBlocked(this)) {
            launchLogin(fromSessionExpiry = true)
            return
        }

        DataInitializer.init(applicationContext)
        WebViewHelper.ensureWebView(applicationContext)
        SyncScheduler.scheduleAll(this)
        configureSystemBarsForLegacyDevices()

        setContentView(R.layout.activity_main)

        if (savedInstanceState != null) {
            currentFragmentId = savedInstanceState.getInt(KEY_CURRENT_FRAGMENT_ID, View.NO_ID)
            currentFragment =
                supportFragmentManager.findFragmentById(R.id.nav_host_fragment)
        }

        swipeRefreshLayout = findViewById(R.id.swipeRefreshLayout)
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

        val toolbar: MaterialToolbar = findViewById(R.id.topAppBar)
        setSupportActionBar(toolbar)

        ViewCompat.setOnApplyWindowInsetsListener(toolbar) { v, insets ->
            val statusBarHeight = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            v.setPadding(v.paddingLeft, statusBarHeight, v.paddingRight, v.paddingBottom)
            insets
        }

        bottomNav = findViewById(R.id.bottom_navigation)
        bottomNavContainer = findViewById(R.id.bottom_nav_container)
        navRail = findViewById(R.id.navigation_rail)

        ViewCompat.setOnApplyWindowInsetsListener(bottomNavContainer) { v, insets ->
            val systemBarsInsets = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val layoutParams = v.layoutParams as ViewGroup.MarginLayoutParams
            val originalMarginBottom = (20 * resources.displayMetrics.density).toInt()
            layoutParams.bottomMargin = originalMarginBottom + systemBarsInsets.bottom
            v.layoutParams = layoutParams
            insets
        }

        val rootView = findViewById<View>(R.id.main)
        rootView.viewTreeObserver.addOnGlobalLayoutListener(object :
            ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                if (isLayoutReady) return
                rootView.viewTreeObserver.removeOnGlobalLayoutListener(this)
                isLayoutReady = true
                configureNavigationForDevice()
                // ★ BUG 53: handleIntent só roda UMA VEZ, evitando ping-pong
                // após rotação de tela.
                if (savedInstanceState == null) handleIntent(intent)
            }
        })

        if (savedInstanceState == null) {
            navigateToHome()
            checkForUpdateOnOpen()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // ★ BUG 23: reporta o resultado ao PermissionHelper
        PermissionHelper.onPermissionResult(this, requestCode)
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this, Manifest.permission.POST_NOTIFICATIONS
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

    /**
     * ★ BUG 47: era chamada suspend mas bloqueante por dentro (sonda nativa).
     * Agora é sempre chamada em Dispatchers.IO.
     */
    suspend fun isOnline(): Boolean = withContext(Dispatchers.IO) {
        NetworkChecker.isOnline()
    }

    suspend fun checkConnectionAndSession(): String {
        val status = SessionManager.checkConnectionAndSession()
        if (status == SessionManager.STATUS_LOGIN_NEEDED && !isFinishing && !isDestroyed) {
            withContext(Dispatchers.Main) { launchLogin(fromSessionExpiry = true) }
        }
        return status
    }

    /**
     * ★ BUG 5 e 54: invalida a sessão local e limpa o flag de login,
     * evitando loop LoginActivity ↔ MainActivity quando as credenciais
     * salvas são inválidas.
     */
    /** ★ PROBLEMA 3: re-login silencioso antes de incomodar o usuário. */
    private fun attemptSilentLogin() {
        appScope.launch {
            val result = withContext(Dispatchers.IO) { RustSession.ensureFeiSession() }
            if (isFinishing || isDestroyed) return@launch
            when (result) {
                is SessionResult.Ok -> {
                    CredentialsStore.setLoggedIn(this@MainActivity, true)
                    recreate()
                }
                SessionResult.NoCredentials -> launchLogin(fromSessionExpiry = false)
                else -> {
                    // Rede/auth temporária: entra no app em modo offline com cache.
                    // Nunca marca logout por isso.
                    CredentialsStore.setLoggedIn(this@MainActivity, true)
                    recreate()
                }
            }
        }
    }

    private fun launchLogin(fromSessionExpiry: Boolean) {
        if (isFinishing || isDestroyed) return
        if (fromSessionExpiry) {
            appScope.launch {
                SessionManager.invalidateLocalSession(
                    clearData = false,
                    markLoggedOut = true
                )
                openLoginScreen(true)
            }
        } else {
            openLoginScreen(false)
        }
    }

    private fun openLoginScreen(fromSessionExpiry: Boolean) {
        if (isFinishing || isDestroyed) return
        startActivity(
            Intent(this, LoginActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                putExtra(LoginActivity.EXTRA_FROM_SESSION_EXPIRY, fromSessionExpiry)
            }
        )
        finish()
    }

    override fun onResume() {
        super.onResume()
        if (isFinishing || isDestroyed || loginAttempted) return   // ★ guarda
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

    /**
     * ★ BUG 53: limpa o extra "destination" após consumir, evitando que
     * rotação de tela re-navegue.
     */
    private fun handleIntent(intent: Intent?) {
        val destination = intent?.getStringExtra("destination")
        if (destination == null) {
            val hasRestoredFragment =
                supportFragmentManager.findFragmentById(R.id.nav_host_fragment) != null
            if (!hasRestoredFragment) navigateToHome()
            return
        }
        intent.removeExtra("destination")
        if (isModoResponsavel && destination != "boletos") return
        when (destination) {
            "notas" -> openFragment(R.id.navigation_notas)
            "horarios" -> openFragment(R.id.option_horarios_aula)
            "provas" -> openFragment(R.id.option_calendario_provas)
            "boletos" -> openFragment(R.id.option_boletos)
        }
    }

    fun openFragment(fragmentId: Int) {
        if (isFinishing || isDestroyed) return
        swipeRefreshLayout.isRefreshing = false
        val fragment = when (fragmentId) {
            R.id.navigation_home -> HomeFragment()
            R.id.navigation_moodle -> MoodleFragment()
            R.id.option_calendario_provas -> CalendarioProvas()
            R.id.navigation_notas -> NotasFragment()
            R.id.option_horarios_aula -> HorariosAula()
            R.id.action_profile -> ProfileFragment()
            R.id.navigation_more -> MoreFragment()
            R.id.option_boletos -> BoletosFragment()
            else -> return
        }
        if (supportFragmentManager.backStackEntryCount > 0) {
            supportFragmentManager.popBackStackImmediate(null, FragmentManager.POP_BACK_STACK_INCLUSIVE)
        }
        currentFragment = fragment
        currentFragmentId = fragmentId
        supportFragmentManager.beginTransaction()
            .replace(R.id.nav_host_fragment, fragment)
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
                if (resources.getBoolean(R.bool.isTablet)) {
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
        val isTablet = resources.getBoolean(R.bool.isTablet)
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
                val rootView: View = findViewById(R.id.main)
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
            .replace(R.id.nav_host_fragment, fragment)
            .addToBackStack(null)
            .commit()
        updateMenuSelection(View.NO_ID)
        updateRefreshLayoutState()
        showBottomNavigation()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.top_app_bar_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            R.id.action_profile -> {
                openFragment(R.id.action_profile)
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    fun navigateToHome() {
        if (isModoResponsavel) {
            openFragment(R.id.option_boletos)
        } else {
            openFragment(R.id.navigation_home)
            if (::bottomNavContainer.isInitialized) {
                bottomNavContainer.postDelayed({
                    showBottomNavigation()
                }, 100)
            }
        }
    }

    private fun checkForUpdateOnOpen() {
        if (hasCheckedUpdateOnOpen || isFinishing || isDestroyed) return
        hasCheckedUpdateOnOpen = true
        lifecycleScope.launch {
            // ★ BUG 47: já está dentro do scope Main, então isOnline() é suspend
            // e não trava a main thread.
            if (!isOnline()) return@launch
            UpdateChecker.checkForUpdate(
                this@MainActivity,
                true,
                object : UpdateChecker.UpdateListener {
                    override fun onUpdateAvailable(
                        url: String, version: String, releaseNotes: String
                    ) {
                        runOnUiThread {
                            if (!isFinishing && !isDestroyed)
                                showUpdatePrePrompt(url, version, releaseNotes)
                        }
                    }
                    override fun onUpToDate() {
                        runOnUiThread {
                            if (!isFinishing && !isDestroyed) resetUpdateSkipCount()
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
        apkUrl: String, version: String, releaseNotes: String
    ) {
        if (isFinishing || isDestroyed) return
        val skipCount = getUpdateSkipCount(version)
        val forced = skipCount >= MAX_UPDATE_SKIPS
        val builder = MaterialAlertDialogBuilder(this)
        builder.setTitle(getString(R.string.update_available_title))
        builder.setMessage(getString(R.string.update_available_message))
        builder.setCancelable(false)
        builder.setPositiveButton(getString(R.string.sim)) { _, _ ->
            showUpdateReleasePrompt(apkUrl, version, releaseNotes, forced)
        }
        if (forced) {
            builder.setNegativeButton(getString(R.string.nao), null)
        } else {
            builder.setNegativeButton(getString(R.string.nao)) { _, _ ->
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
        apkUrl: String, version: String, releaseNotes: String, forced: Boolean
    ) {
        if (isFinishing || isDestroyed) return
        val builder = MaterialAlertDialogBuilder(this)
        builder.setTitle(getString(R.string.settings_update_titulo, version))
        builder.setMessage(
            getString(R.string.settings_update_mensagem, releaseNotes)
        )
        builder.setCancelable(false)
        builder.setPositiveButton(getString(R.string.sim)) { _, _ ->
            startManualDownload(apkUrl, forced)
        }
        builder.setNegativeButton(getString(R.string.nao), null)
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
        updateDownloader.startDownload(apkUrl, forced)
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
                            ContextCompat.getColor(
                                this@MainActivity, R.color.nav_bar_dark
                            )
                        } else {
                            ContextCompat.getColor(
                                this@MainActivity, R.color.nav_bar_light
                            )
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
        if (::updateDownloader.isInitialized) {
            updateDownloader.destroy()
        }
        appScope.cancel()
    }
}