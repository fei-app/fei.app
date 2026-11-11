package com.marinov.openfei.ui.webview

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.color.MaterialColors
import com.marinov.openfei.R
import com.marinov.openfei.network.NetworkChecker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class WebViewActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var navigationController: WebViewNavigationController
    private val fileChooserHelper = WebViewFileChooserHelper(this) { this }
    private var loadingOverlay: View? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        configureSystemBarsForLegacyDevices()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_webview)

        webView = findViewById(R.id.webview)

        navigationController = WebViewNavigationController(
            context = this,
            webView = webView,
            scope = lifecycleScope,
            ui = object : WebViewNavigationController.UiCallbacks {
                override fun showLoading() = showLoadingOverlay()
                override fun hideLoading() = hideLoadingOverlay()
            },
            navigation = object : WebViewNavigationController.NavigationCallbacks {
                override fun shouldInterceptHome(): Boolean = false
                override fun navigateHome() {}
                override fun openLogin() {
                    WebViewSessionHelper.openLoginAndFinish(this@WebViewActivity)
                }
                override fun isAlive(): Boolean = !isFinishing && !isDestroyed
            }
        )

        WebViewConfig.configure(webView)
        WebViewConfig.setupDownloadListener(webView, this, showToast = false)
        fileChooserHelper.attachTo(webView)

        webView.webViewClient = @SuppressLint("MissingOnRenderProcessGone")
        object : WebViewClient() {

            override fun shouldOverrideUrlLoading(
                view: WebView?, request: WebResourceRequest?
            ): Boolean = navigationController.handleUrl(request?.url?.toString())

            @Deprecated("Deprecated in Java")
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean =
                navigationController.handleUrl(url)

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                navigationController.onPageStarted(url)
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                hideLoadingOverlay()
                WebViewConfig.disableTransitions(view)
            }

            override fun onReceivedError(
                view: WebView, request: WebResourceRequest, error: WebResourceError
            ) {
                // ★ BUG 46: só a página principal liga a tela de offline.
                if (!request.isForMainFrame) return
                // ★ BUG 47: sonda nativa bloqueante fora da main thread.
                lifecycleScope.launch {
                    val online = withContext(Dispatchers.IO) { NetworkChecker.isOnline() }
                    if (!online && !isFinishing && !isDestroyed) {
                        showOfflineOverlay { checkSessionAndLoad() }
                    }
                }
            }
        }

        setupBackPressHandler()
        checkSessionAndLoad()
    }

    override fun onResume() {
        super.onResume()
        if (::webView.isInitialized) webView.onResume()
    }

    override fun onPause() {
        if (::webView.isInitialized) webView.onPause()
        super.onPause()
    }

    private fun checkSessionAndLoad() {
        val url = intent.getStringExtra(EXTRA_URL) ?: ""
        if (url.isEmpty()) {
            finish()
            return
        }

        lifecycleScope.launch {
            showLoadingOverlay()
            when (WebViewSessionHelper.checkSession()) {
                is WebViewSessionHelper.SessionState.OnlineOk -> {
                    hideLoadingOverlay()
                    WebViewConfig.disableTransitions(webView)
                    webView.loadUrl(url)
                }

                is WebViewSessionHelper.SessionState.Offline -> {
                    showOfflineOverlay { checkSessionAndLoad() }
                }

                is WebViewSessionHelper.SessionState.LoginNeeded -> {
                    hideLoadingOverlay()
                    WebViewSessionHelper.openLoginAndFinish(this@WebViewActivity)
                }
            }
        }
    }

    private fun showLoadingOverlay() {
        if (loadingOverlay != null) return
        val root = findViewById<ViewGroup>(android.R.id.content)
        val overlay = FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(getThemeBackgroundColor())
            isClickable = true
            isFocusable = true
        }
        overlay.addView(
            ProgressBar(this).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER
                )
            }
        )
        root.addView(overlay)
        loadingOverlay = overlay
    }

    private fun showOfflineOverlay(onRetry: () -> Unit) {
        hideLoadingOverlay()
        val root = findViewById<ViewGroup>(android.R.id.content)
        val padding = (24 * resources.displayMetrics.density).toInt()

        val overlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(getThemeBackgroundColor())
            isClickable = true
            isFocusable = true
            setPadding(padding, padding, padding, padding)
        }

        val message = TextView(this).apply {
            setText(R.string.sem_conexao_internet)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (16 * resources.displayMetrics.density).toInt() }
        }

        val retryButton = Button(this).apply {
            setText(R.string.try_again)
            setOnClickListener { onRetry() }
        }

        overlay.addView(message)
        overlay.addView(retryButton)
        root.addView(overlay)
        loadingOverlay = overlay
    }

    private fun hideLoadingOverlay() {
        loadingOverlay?.let { overlay -> (overlay.parent as? ViewGroup)?.removeView(overlay) }
        loadingOverlay = null
    }

    private fun getThemeBackgroundColor(): Int = MaterialColors.getColor(
        this, com.google.android.material.R.attr.colorSurface, Color.WHITE
    )

    private fun setupBackPressHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (::webView.isInitialized && webView.canGoBack()) {
                    webView.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    /** ★ BUG 26: o WebView era vazado junto com a Activity inteira. */
    override fun onDestroy() {
        navigationController.onCleared()
        if (::webView.isInitialized) {
            runCatching {
                webView.stopLoading()
                webView.loadUrl("about:blank")
                (webView.parent as? ViewGroup)?.removeView(webView)
                webView.destroy()
            }
        }
        super.onDestroy()
    }

    companion object {
        const val EXTRA_URL = "extra_url"

        fun start(context: Context, url: String) {
            context.startActivity(
                Intent(context, WebViewActivity::class.java).putExtra(EXTRA_URL, url)
            )
        }
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
                navigationBarColor = ContextCompat.getColor(
                    this@WebViewActivity,
                    if (isDarkMode) R.color.nav_bar_dark else R.color.nav_bar_light
                )
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