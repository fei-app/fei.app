package com.marinov.colegioetapa

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.behavior.HideBottomViewOnScrollBehavior
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.color.MaterialColors
import com.google.android.material.navigationrail.NavigationRailView
import java.util.concurrent.TimeUnit
import androidx.core.view.size
import androidx.core.view.get

class MainActivity : AppCompatActivity(), WebViewFragment.LoginSuccessListener {

    interface RefreshableFragment {
        fun onRefresh()
    }

    companion object {
        private const val REQUEST_NOTIFICATION_PERMISSION = 100
        private const val TAG = "MainActivity"
        private const val KEY_CURRENT_FRAGMENT_ID = "current_fragment_id"

        private val REFRESHABLE_FRAGMENTS = setOf(
            R.id.navigation_notas,
            R.id.option_horarios_aula,
            R.id.action_profile,
            R.id.navigation_more
        )

        const val PREFS_NAME = "app_prefs"
        private const val PREF_KEY_BATTERY_REQUESTED = "battery_request_done"
        const val KEY_SAFE_MODE = "safe_mode"

        private val SAFE_MODE_BLOCKED_NAV = setOf(
            R.id.navigation_notas,
            R.id.option_horarios_aula,
            R.id.option_calendario_provas
        )

        private const val ALPHA_DISABLED = 0.38f
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

    fun isSafeMode(): Boolean =
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getBoolean(KEY_SAFE_MODE, true)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureSystemBarsForLegacyDevices()
        MaterialColors.getColor(
            this,
            com.google.android.material.R.attr.colorPrimaryContainer,
            Color.BLACK
        )
        setContentView(R.layout.activity_main)

        // Restaura estado após morte do processo
        if (savedInstanceState != null) {
            currentFragmentId = savedInstanceState.getInt(KEY_CURRENT_FRAGMENT_ID, View.NO_ID)
            currentFragment = supportFragmentManager.findFragmentById(R.id.nav_host_fragment)
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

        // CORREÇÃO 1: Tratar insets do sistema para dispositivos com navegação por gestos (Android 10+)
        // Isso garante que o bottom margin correto seja aplicado e o HideBottomViewOnScrollBehavior suma com tudo
        ViewCompat.setOnApplyWindowInsetsListener(bottomNavContainer) { v, insets ->
            val systemBarsInsets = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val layoutParams = v.layoutParams as ViewGroup.MarginLayoutParams
            // 20dp original do XML convertido para pixels
            val originalMarginBottom = (20 * resources.displayMetrics.density).toInt()
            layoutParams.bottomMargin = originalMarginBottom + systemBarsInsets.bottom
            v.layoutParams = layoutParams
            insets
        }

        val rootView = findViewById<View>(R.id.main)
        rootView.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                if (isLayoutReady) return
                rootView.viewTreeObserver.removeOnGlobalLayoutListener(this)
                isLayoutReady = true
                configureNavigationForDevice()
                handleIntent(intent)
            }
        })
        solicitarPermissaoNotificacao()
        solicitarIsencaoOtimizacaoBateria()

        verificarModoSeguranca()
        iniciarUpdateWorker()
        navigateToHome()
    }

    // CORREÇÃO 2: Função global para forçar a barra inferior a subir
    fun showBottomNavigation() {
        if (::bottomNavContainer.isInitialized && bottomNavContainer.visibility == View.VISIBLE) {
            val layoutParams = bottomNavContainer.layoutParams as? CoordinatorLayout.LayoutParams
            @Suppress("UNCHECKED_CAST")
            val behavior = layoutParams?.behavior as? HideBottomViewOnScrollBehavior<View>
            behavior?.slideUp(bottomNavContainer)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_CURRENT_FRAGMENT_ID, currentFragmentId)
    }

    override fun onResume() {
        super.onResume()
        verificarModoSeguranca()
    }

    private fun verificarModoSeguranca() {
        val safeMode = isSafeMode()

        if (safeMode) {
            WorkManager.getInstance(this).cancelUniqueWork("NotasWorkerTask")
        } else {
            iniciarNotasWorker()
        }

        if (safeMode) {
            val blockedFragments = SAFE_MODE_BLOCKED_NAV + R.id.action_profile
            if (blockedFragments.contains(currentFragmentId)) {
                navigateToHome()
            }
        }

        if (isLayoutReady) {
            configureNavigationForDevice()
            invalidateOptionsMenu()
        }
    }

    private fun isRefreshEnabled(): Boolean {
        return when {
            currentFragmentId == View.NO_ID -> currentFragment is RefreshableFragment
            else -> REFRESHABLE_FRAGMENTS.contains(currentFragmentId)
        }
    }

    private fun updateRefreshLayoutState() {
        swipeRefreshLayout.isEnabled = isRefreshEnabled()
        if (!isRefreshEnabled()) {
            swipeRefreshLayout.isRefreshing = false
        }
    }

    fun setRefreshing(refreshing: Boolean) {
        if (isRefreshEnabled()) {
            swipeRefreshLayout.isRefreshing = refreshing
        } else {
            swipeRefreshLayout.isRefreshing = false
        }
    }

    override fun onLoginSuccess() {
        (supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as? HomeFragment)?.onLoginSuccess()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (isLayoutReady) handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val destination = intent?.getStringExtra("destination") ?: run {
            val hasRestoredFragment = supportFragmentManager
                .findFragmentById(R.id.nav_host_fragment) != null
            if (!hasRestoredFragment) openFragment(R.id.navigation_home)
            return
        }
        Log.d(TAG, "Handling intent with destination: $destination")
        when (destination) {
            "notas"   -> if (!isSafeMode()) openFragment(R.id.navigation_notas)
            "horarios"-> if (!isSafeMode()) openFragment(R.id.option_horarios_aula)
            "provas"  -> if (!isSafeMode()) openFragment(R.id.option_calendario_provas)
        }
    }

    private fun openFragment(fragmentId: Int) {
        if (isFinishing || isDestroyed) return

        val blockedIds = SAFE_MODE_BLOCKED_NAV + R.id.action_profile
        if (isSafeMode() && blockedIds.contains(fragmentId)) return

        swipeRefreshLayout.isRefreshing = false
        Log.d(TAG, "Opening fragment: $fragmentId")

        val fragment = when (fragmentId) {
            R.id.navigation_home          -> HomeFragment()
            R.id.option_calendario_provas -> CalendarioProvas()
            R.id.navigation_notas         -> NotasFragment()
            R.id.option_horarios_aula     -> HorariosAula()
            R.id.action_profile           -> ProfileFragment()
            R.id.navigation_more          -> MoreFragment()
            else -> return
        }
        currentFragment = fragment
        currentFragmentId = fragmentId

        supportFragmentManager.beginTransaction()
            .setCustomAnimations(android.R.anim.fade_in, android.R.anim.fade_out)
            .replace(R.id.nav_host_fragment, fragment)
            .commit()

        updateMenuSelection(fragmentId)
        updateRefreshLayoutState()
        showBottomNavigation() // CORREÇÃO 2: Força a barra inferior a reaparecer ao trocar de tela
    }

    private fun updateMenuSelection(fragmentId: Int) {
        if (isUpdatingSelection) return
        isUpdatingSelection = true
        runOnUiThread {
            try {
                if (resources.getBoolean(R.bool.isTablet)) {
                    if (navRail.selectedItemId != fragmentId) navRail.selectedItemId = fragmentId
                } else {
                    if (bottomNav.selectedItemId != fragmentId) bottomNav.selectedItemId = fragmentId
                }
            } finally {
                isUpdatingSelection = false
            }
        }
    }

    private fun configureNavigationForDevice() {
        val isTablet = resources.getBoolean(R.bool.isTablet)
        val safeMode = isSafeMode()

        if (isTablet) {
            bottomNavContainer.visibility = View.GONE
            navRail.visibility = View.VISIBLE

            SAFE_MODE_BLOCKED_NAV.forEach { id ->
                navRail.menu.findItem(id)?.isEnabled = !safeMode
            }
            navRail.post { updateNavItemsAlpha(navRail, navRail.menu, safeMode) }

            navRail.setOnItemSelectedListener { item ->
                if (safeMode && SAFE_MODE_BLOCKED_NAV.contains(item.itemId)) return@setOnItemSelectedListener false
                if (!isUpdatingSelection) openFragment(item.itemId)
                true
            }

        } else {
            navRail.visibility = View.GONE
            bottomNavContainer.visibility = View.VISIBLE

            SAFE_MODE_BLOCKED_NAV.forEach { id ->
                bottomNav.menu.findItem(id)?.isEnabled = !safeMode
            }
            bottomNav.post { updateNavItemsAlpha(bottomNav, bottomNav.menu, safeMode) }

            bottomNav.setOnItemSelectedListener { item ->
                if (safeMode && SAFE_MODE_BLOCKED_NAV.contains(item.itemId)) return@setOnItemSelectedListener false
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

    private fun updateNavItemsAlpha(navView: ViewGroup, menu: Menu, safeMode: Boolean) {
        val menuSize = menu.size
        if (menuSize == 0) return
        val menuContainer = findNavMenuContainer(navView, menuSize) ?: return
        for (i in 0 until menuSize) {
            val itemId = menu[i].itemId
            if (SAFE_MODE_BLOCKED_NAV.contains(itemId)) {
                menuContainer.getChildAt(i)?.alpha = if (safeMode) ALPHA_DISABLED else 1.0f
            }
        }
    }

    private fun findNavMenuContainer(parent: ViewGroup, menuSize: Int): ViewGroup? {
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i) as? ViewGroup ?: continue
            if (child.childCount == menuSize) return child
        }
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i) as? ViewGroup ?: continue
            for (j in 0 until child.childCount) {
                val grandchild = child.getChildAt(j) as? ViewGroup ?: continue
                if (grandchild.childCount == menuSize) return grandchild
            }
        }
        return null
    }

    private fun iniciarUpdateWorker() {
        val updateWork = PeriodicWorkRequest.Builder(
            UpdateCheckWorker::class.java,
            15,
            TimeUnit.MINUTES
        ).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "UpdateCheckWorker",
            ExistingPeriodicWorkPolicy.KEEP,
            updateWork
        )
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
                        @Suppress("DEPRECATION") statusBarColor = Color.BLACK
                        @Suppress("DEPRECATION") navigationBarColor = Color.BLACK
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
                            ContextCompat.getColor(this@MainActivity, R.color.nav_bar_dark)
                        } else {
                            ContextCompat.getColor(this@MainActivity, R.color.nav_bar_light)
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

    private fun solicitarPermissaoNotificacao() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    REQUEST_NOTIFICATION_PERMISSION
                )
            }
        }
    }

    private fun iniciarNotasWorker() {
        val notasWork = PeriodicWorkRequest.Builder(
            NotasWorker::class.java,
            15,
            TimeUnit.MINUTES
        ).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "NotasWorkerTask",
            ExistingPeriodicWorkPolicy.KEEP,
            notasWork
        )
    }

    @SuppressLint("BatteryLife")
    private fun solicitarIsencaoOtimizacaoBateria() {
        try {
            val pm = getSystemService(POWER_SERVICE) as? PowerManager
            if (pm == null) { Log.w(TAG, "PowerManager não disponível"); return }
            if (pm.isIgnoringBatteryOptimizations(packageName)) return

            val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            if (prefs.getBoolean(PREF_KEY_BATTERY_REQUESTED, false)) return

            try {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                intent.data = "package:$packageName".toUri()
                startActivity(intent)
            } catch (e: ActivityNotFoundException) {
                Log.w(TAG, "ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS não disponível", e)
                try { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
                catch (ex: Exception) { Log.w(TAG, "Não foi possível abrir configurações de bateria", ex) }
            } catch (e: SecurityException) {
                Log.w(TAG, "SecurityException ao requisitar isenção", e)
                try { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
                catch (ex: Exception) { Log.w(TAG, "Não foi possível abrir configurações de bateria", ex) }
            } catch (e: Exception) {
                Log.w(TAG, "Erro ao abrir solicitação de isenção de bateria", e)
                try { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
                catch (ex: Exception) { Log.w(TAG, "Não foi possível abrir configurações de bateria", ex) }
            } finally {
                prefs.edit { putBoolean(PREF_KEY_BATTERY_REQUESTED, true) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Erro ao verificar isenção de otimização de bateria", e)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
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
        showBottomNavigation() // CORREÇÃO 2: Força a barra inferior a reaparecer
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.top_app_bar_menu, menu)
        val profileItem = menu.findItem(R.id.action_profile)
        if (isSafeMode()) {
            profileItem?.isEnabled = false
            profileItem?.icon?.let { icon ->
                val drawable = DrawableCompat.wrap(icon).mutate()
                drawable.alpha = (255 * ALPHA_DISABLED).toInt()
                profileItem.icon = drawable
            }
        } else {
            profileItem?.isEnabled = true
            profileItem?.icon?.let { icon ->
                val drawable = DrawableCompat.wrap(icon).mutate()
                drawable.alpha = 255
                profileItem.icon = drawable
            }
        }
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            R.id.action_profile -> {
                if (isSafeMode()) return true
                openFragment(R.id.action_profile)
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    fun abrirDetalhesProva(url: String) {
        val fragment = DetalhesProvaFragment.newInstance(url)
        supportFragmentManager.beginTransaction()
            .setCustomAnimations(android.R.anim.fade_in, android.R.anim.fade_out)
            .replace(R.id.nav_host_fragment, fragment)
            .addToBackStack(null)
            .commit()
    }

    fun navigateToHome() {
        openFragment(R.id.navigation_home)
    }
}