package com.marinov.openfei.ui.webview

import android.annotation.SuppressLint
import android.app.Activity
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.fragment.app.Fragment
import com.google.android.material.behavior.HideBottomViewOnScrollBehavior
import com.google.android.material.button.MaterialButton
import androidx.core.net.toUri
import com.marinov.openfei.R
import com.marinov.openfei.ui.main.MainActivity

class WebViewFragment : Fragment() {
    private lateinit var webView: WebView
    private lateinit var layoutSemInternet: LinearLayout
    private lateinit var btnTentarNovamente: MaterialButton
    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    // ★ NOVO: controle de auto-hide da barra de navegação inferior ao scrollar o WebView ★
    private var bottomNavContainer: View? = null
    private var bottomNavBehavior: HideBottomViewOnScrollBehavior<View>? = null

    private val fileChooserLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val data = result.data
            val results: Array<Uri>? = if (data?.data != null) {
                arrayOf(data.data!!)
            } else if (data?.clipData != null) {
                val count = data.clipData!!.itemCount
                Array(count) { i -> data.clipData!!.getItemAt(i).uri }
            } else {
                null
            }
            filePathCallback?.onReceiveValue(results)
        } else {
            filePathCallback?.onReceiveValue(null)
        }
        filePathCallback = null
    }

    companion object {
        private const val ARG_URL = "url"
        private const val ARG_EXIT_TO_HOME = "exit_to_home"
        private const val HOME_URL_IDENTIFIER = "https://interage.fei.org.br/secureserver/portal/graduacao/home"

        @JvmStatic
        fun createArgs(url: String, exitToHome: Boolean = false): Bundle = Bundle().apply {
            putString(ARG_URL, url)
            putBoolean(ARG_EXIT_TO_HOME, exitToHome)
        }
    }

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        retainInstance = true
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_webview, container, false)
        webView = view.findViewById(R.id.webview)
        layoutSemInternet = view.findViewById(R.id.layout_sem_internet)
        btnTentarNovamente = view.findViewById(R.id.btn_tentar_novamente)

        if (!isOnline()) showNoInternetUI() else initializeWebView()

        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val exitToHome = arguments?.getBoolean(ARG_EXIT_TO_HOME, false) ?: false

        val callback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    if (exitToHome) {
                        (activity as? MainActivity)?.navigateToHome()
                    } else {
                        requireActivity().supportFragmentManager.popBackStack()
                    }
                }
            }
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, callback)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun initializeWebView() {
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
            flush()
        }

        webView.apply {
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            visibility = View.INVISIBLE
        }

        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            userAgentString = "Mozilla/5.0 (Linux; Android 16; sdk_gphone64_x86_64 Build/BE2A.250530.026.D1; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/133.0.6943.137 Mobile Safari/537.36"
            databaseEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }

        setupWebViewSecurity()
        setupBottomNavAutoHide() // ★ NOVO ★

        webView.webViewClient = @SuppressLint("MissingOnRenderProcessGone")
        object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val headers = request?.requestHeaders?.toMutableMap()
                headers?.remove("X-Requested-With")
                return super.shouldInterceptRequest(view, request)
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url.toString()
                return handleUrlOverride(url)
            }

            @Deprecated("Deprecated in Java")
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                return handleUrlOverride(url)
            }

            // ★ NOVO: ao iniciar o carregamento de uma nova página, a barra volta a aparecer ★
            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                showBottomNav()
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                showWebViewWithAnimation(view)
                layoutSemInternet.visibility = View.GONE
                showBottomNav() // ★ NOVO: garante a barra visível ao final do carregamento ★
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (!isOnline()) showNoInternetUI()
            }
        }

        webView.setDownloadListener { url, userAgent, contentDisposition, mimetype, _ ->
            try {
                val request = DownloadManager.Request(url.toUri())
                request.setMimeType(mimetype)
                val cookies = CookieManager.getInstance().getCookie(url)
                request.addRequestHeader("cookie", cookies)
                request.addRequestHeader("User-Agent", userAgent)
                request.setDescription(getString(R.string.baixando_arquivo))
                val fileName = URLUtil.guessFileName(url, contentDisposition, mimetype)
                request.setTitle(fileName)
                request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                val dm = requireContext().getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                dm.enqueue(request)
                Toast.makeText(requireContext(), getString(R.string.download_iniciado), Toast.LENGTH_SHORT).show()
            } catch (_: Exception) {
                Toast.makeText(requireContext(), getString(R.string.erro_ao_iniciar_download), Toast.LENGTH_SHORT).show()
            }
        }

        arguments?.getString(ARG_URL)?.let { webView.loadUrl(it) }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                this@WebViewFragment.filePathCallback?.onReceiveValue(null)
                this@WebViewFragment.filePathCallback = filePathCallback
                val intent = fileChooserParams?.createIntent()
                try {
                    fileChooserLauncher.launch(intent)
                } catch (_: ActivityNotFoundException) {
                    this@WebViewFragment.filePathCallback = null
                    Toast.makeText(requireContext(), getString(R.string.nenhum_app_para_arquivos), Toast.LENGTH_LONG).show()
                    return false
                }
                return true
            }
        }
    }

    // ★ NOVO: configura a detecção de scroll do WebView e conecta ao HideBottomViewOnScrollBehavior
    // já anexado ao bottom_nav_container via app:layout_behavior no activity_main.xml ★
    @SuppressLint("UseRequiresApi")
    private fun setupBottomNavAutoHide() {
        val container = requireActivity().findViewById<View>(R.id.bottom_nav_container) ?: return
        bottomNavContainer = container

        val params = container.layoutParams as? CoordinatorLayout.LayoutParams
        @Suppress("UNCHECKED_CAST")
        bottomNavBehavior = params?.behavior as? HideBottomViewOnScrollBehavior<View>

        webView.setOnScrollChangeListener { _, _, scrollY, _, oldScrollY ->
            if (scrollY > oldScrollY) {
                bottomNavBehavior?.slideDown(container)
            } else if (scrollY < oldScrollY) {
                bottomNavBehavior?.slideUp(container)
            }
        }
    }

    private fun showBottomNav() {
        val container = bottomNavContainer ?: return
        bottomNavBehavior?.slideUp(container)
    }

    private fun handleUrlOverride(url: String?): Boolean {
        if (url == null) return false
        if (isHomeUrl(url)) {
            Handler(Looper.getMainLooper()).post {
                (activity as? MainActivity)?.navigateToHome()
            }
            return true
        }
        val uri = url.toUri()
        val host = uri.host ?: return false
        if (host.endsWith("fei.edu.br") || host.endsWith("fei.org.br")) {
            return false
        }
        try {
            val intent = Intent(Intent.ACTION_VIEW, uri)
            requireContext().startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(requireContext(), getString(R.string.nenhum_navegador_encontrado), Toast.LENGTH_SHORT).show()
        }
        return true
    }

    private fun isHomeUrl(url: String?): Boolean {
        return url?.contains(HOME_URL_IDENTIFIER) == true
    }

    private fun setupWebViewSecurity() {
        webView.apply {
            setOnLongClickListener { true }
            isLongClickable = false
            isHapticFeedbackEnabled = false
        }
    }

    private fun showWebViewWithAnimation(view: WebView) {
        Handler(Looper.getMainLooper()).postDelayed({
            view.alpha = 0f
            view.visibility = View.VISIBLE
            view.animate().alpha(1f).duration = 300
        }, 100)
    }

    private fun isOnline(): Boolean {
        val cm = requireContext().getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager?
            ?: return false
        return cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }

    private fun showNoInternetUI() {
        webView.visibility = View.GONE
        layoutSemInternet.visibility = View.VISIBLE
        btnTentarNovamente.setOnClickListener {
            if (isOnline()) {
                layoutSemInternet.visibility = View.GONE
                webView.reload()
            } else {
                Toast.makeText(requireContext(), getString(R.string.sem_conexao_internet), Toast.LENGTH_SHORT).show()            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
    }

    override fun onDestroyView() {
        // ★ NOVO: restaura a barra visível ao sair desta tela, evitando que fique escondida em outras telas ★
        showBottomNav()
        if (::webView.isInitialized) webView.destroy()
        super.onDestroyView()
    }
}