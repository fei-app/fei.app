package com.marinov.openfei

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Rect
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.webkit.CookieManager
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
import androidx.lifecycle.lifecycleScope
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.behavior.HideBottomViewOnScrollBehavior
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.color.MaterialColors
import com.google.android.material.navigationrail.NavigationRailView
import kotlinx.coroutines.Dispatchers
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

        private val REFRESHABLE_FRAGMENTS = setOf(
            R.id.navigation_notas,
            R.id.action_profile,
            R.id.navigation_more
        )

        const val STATUS_OFFLINE      = "0"  // sem conexão → usar cache
        const val STATUS_ONLINE_OK    = "1"  // online + cookie válido (sessão presente)
        const val STATUS_LOGIN_NEEDED = "A"  // online + cookies expirados ou ausentes → LoginActivity já lançada

        private const val HOME_URL = "https://interage.fei.org.br/secureserver/portal/graduacao/home"
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!isUserLoggedIn()) {
            launchLogin()
            return
        }

        Dados.init(applicationContext)

        configureSystemBarsForLegacyDevices()
        MaterialColors.getColor(
            this,
            com.google.android.material.R.attr.colorPrimaryContainer,
            Color.BLACK
        )
        setContentView(R.layout.activity_main)

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

        ViewCompat.setOnApplyWindowInsetsListener(bottomNavContainer) { v, insets ->
            val systemBarsInsets = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val layoutParams = v.layoutParams as ViewGroup.MarginLayoutParams
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

        // Inicia o serviço de segundo plano (substitui WorkerManagerHelper)
        BackgroundService.start(this)

        if (savedInstanceState == null) {
            navigateToHome()
        }
    }

    // ===================== PERMISSÃO DE NOTIFICAÇÃO =====================

    /**
     * Solicita POST_NOTIFICATIONS toda vez que o app fica visível.
     * No Android < 13 é no-op. Se o usuário negou permanentemente,
     * requestPermissions() retorna silenciosamente sem crash.
     */
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

    // ===================== ESTADO DE LOGIN =====================

    private fun isUserLoggedIn(): Boolean {
        val prefs = LoginActivity.getEncryptedPrefs(this)
        return prefs.getBoolean(LoginActivity.KEY_IS_LOGGED_IN, false)
    }

    // ===================== CONEXÃO / SESSÃO =====================

    fun isOnline(): Boolean {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    suspend fun checkConnectionAndSession(): String {
        if (!isOnline()) return STATUS_OFFLINE

        return withContext(Dispatchers.IO) {
            try {
                val cookies = CookieManager.getInstance().getCookie(HOME_URL)

                if (!cookies.isNullOrBlank()) {
                    Log.d(TAG, "checkConnectionAndSession → sessão/cookies válidos (STATUS_ONLINE_OK)")
                    STATUS_ONLINE_OK
                } else {
                    Log.w(TAG, "checkConnectionAndSession → cookies ausentes ou expirados (STATUS_LOGIN_NEEDED)")
                    withContext(Dispatchers.Main) { launchLogin() }
                    STATUS_LOGIN_NEEDED
                }
            } catch (e: Exception) {
                Log.w(TAG, "checkConnectionAndSession → erro tratado como offline: ${e.message}")
                STATUS_OFFLINE
            }
        }
    }

    private fun launchLogin() {
        LoginActivity.getEncryptedPrefs(this).edit {
            putBoolean(LoginActivity.KEY_IS_LOGGED_IN, false)
        }
        startActivity(
            Intent(this, LoginActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
        )
        finish()
    }

    // ===================== CICLO DE VIDA =====================

    override fun onResume() {
        super.onResume()
        // Solicita permissão de notificação toda vez que o app fica em primeiro plano
        requestNotificationPermissionIfNeeded()
        if (isLayoutReady) {
            configureNavigationForDevice()
            invalidateOptionsMenu()
        }
        lifecycleScope.launch {
            checkConnectionAndSession()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_CURRENT_FRAGMENT_ID, currentFragmentId)
    }

    // ===================== REFRESH =====================

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

    // ===================== NAVEGAÇÃO =====================

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
            val hasRestoredFragment = supportFragmentManager
                .findFragmentById(R.id.nav_host_fragment) != null
            if (!hasRestoredFragment) openFragment(R.id.navigation_home)
            return
        }
        Log.d(TAG, "Handling intent with destination: $destination")
        when (destination) {
            "notas"   -> openFragment(R.id.navigation_notas)
            "horarios"-> openFragment(R.id.option_horarios_aula)
            "provas"  -> openFragment(R.id.option_calendario_provas)
            "boletos" -> openFragment(R.id.option_boletos)
        }
    }

    private fun openFragment(fragmentId: Int) {
        if (isFinishing || isDestroyed) return

        swipeRefreshLayout.isRefreshing = false
        Log.d(TAG, "Opening fragment: $fragmentId")

        val fragment = when (fragmentId) {
            R.id.navigation_home          -> HomeFragment()
            R.id.option_calendario_provas -> CalendarioProvas()
            R.id.navigation_notas         -> NotasFragment()
            R.id.option_horarios_aula     -> HorariosAula()
            R.id.action_profile           -> ProfileFragment()
            R.id.navigation_more          -> MoreFragment()
            R.id.option_boletos           -> BoletosFragment()
            else -> return
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
        openFragment(R.id.navigation_home)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // Resultado da permissão de notificação — nenhuma ação obrigatória,
        // o serviço verifica a permissão antes de cada notify().
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
}