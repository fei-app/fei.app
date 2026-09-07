package com.marinov.openfei.ui.webview

import android.annotation.SuppressLint
import android.app.Activity
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Log
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
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.behavior.HideBottomViewOnScrollBehavior
import com.google.android.material.button.MaterialButton
import androidx.core.net.toUri
import com.marinov.openfei.R
import com.marinov.openfei.data.NetworkChecker
import com.marinov.openfei.data.SessionManager
import com.marinov.openfei.ui.login.LoginActivity
import com.marinov.openfei.ui.main.MainActivity
import kotlinx.coroutines.launch

class WebViewFragment : Fragment() {
    private lateinit var webView: WebView
    private lateinit var layoutSemInternet: LinearLayout
    private lateinit var btnTentarNovamente: MaterialButton
    private lateinit var loadingContainer: FrameLayout
    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    // ★ NOVO: controla se este WebView está protegendo os cookies do Moodle ★
    private var isMoodleProtectionOwner = false

    // ★ NOVO: evita reentrância no fluxo de redirecionamento de /login/ do Moodle ★
    private var handlingMoodleLoginRedirect = false

    // ★ controle de auto-hide da barra de navegação inferior ao scrollar o WebView ★
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
        private const val TAG = "WebViewFragment"
        private const val ARG_URL = "url"
        private const val ARG_EXIT_TO_HOME = "exit_to_home"
        // ★ NOVO: contador de tentativas consecutivas de renovação do Moodle ★
        private var moodleLoginRetryCount = 0
        private const val HOME_URL_IDENTIFIER = "https://interage.fei.org.br/secureserver/portal/graduacao/home"

        private const val MOODLE_HOST = "moodle.fei.edu.br"
        private const val MOODLE_LOGIN_PATH_PREFIX = "/login/"
        private const val MOODLE_HOME_URL = "https://moodle.fei.edu.br/my/"

        // ★ NOVO: limite de tentativas para não travar em loop infinito ★
        private const val MAX_MOODLE_LOGIN_RETRIES = 2

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
        loadingContainer = view.findViewById(R.id.loading_container)

        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val exitToHome = arguments?.getBoolean(ARG_EXIT_TO_HOME, false) ?: false

        val callback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (::webView.isInitialized && webView.canGoBack()) {
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

        // Inicia a verificação de conexão e sessão
        checkConnectionAndLoad()
    }

    private fun checkConnectionAndLoad() {
        viewLifecycleOwner.lifecycleScope.launch {
            // 1. Verifica primeiramente com o NCSI se tem internet
            val isOnline = NetworkChecker.isOnline()
            if (!isAdded) return@launch

            if (!isOnline) {
                showNoInternetUI()
                return@launch
            }

            // 2. Se tiver online, mostra loading e verifica a sessão
            showLoadingUI()
            val status = SessionManager.checkConnectionAndSession()
            if (!isAdded) return@launch

            when (status) {
                SessionManager.STATUS_ONLINE_OK -> {
                    // 3. Sessão OK, entra no WebView
                    hideLoadingUI()
                    initializeWebView()
                }
                SessionManager.STATUS_LOGIN_NEEDED -> {
                    // 4. Sessão falhou e está online -> chama a LoginActivity
                    hideLoadingUI()
                    Toast.makeText(requireContext(), "Sessão expirada. Faça login novamente.", Toast.LENGTH_LONG).show()
                    val intent = Intent(requireContext(), LoginActivity::class.java)
                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    startActivity(intent)
                    requireActivity().finish()
                }
                SessionManager.STATUS_OFFLINE -> {
                    // Falha de rede durante o login (mesmo o NCSI tendo dito que estava online)
                    hideLoadingUI()
                    showNoInternetUI()
                }
            }
        }
    }

    private fun showLoadingUI() {
        if (!isAdded) return
        webView.visibility = View.GONE
        layoutSemInternet.visibility = View.GONE
        loadingContainer.visibility = View.VISIBLE
    }

    private fun hideLoadingUI() {
        if (!isAdded) return
        loadingContainer.visibility = View.GONE
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun initializeWebView() {
        if (!isAdded) return

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
        setupBottomNavAutoHide()

        // ★ NOVO: se a URL inicial já é do Moodle, protege os cookies desde já ★
        val initialUrl = arguments?.getString(ARG_URL)
        if (initialUrl != null && isMoodleUrl(initialUrl)) {
            protegerCookiesMoodleSeNecessario()
        }

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

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                showBottomNav()

                if (url != null) {
                    if (isMoodleUrl(url)) {
                        protegerCookiesMoodleSeNecessario()
                        if (!isMoodleLoginUrl(url)) {
                            // Chegou numa página válida do Moodle → reseta o contador de retries
                            moodleLoginRetryCount = 0
                        }
                    } else {
                        desprotegerCookiesMoodleSeNecessario()
                    }
                }
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                showWebViewWithAnimation(view)
                layoutSemInternet.visibility = View.GONE
                hideLoadingUI()
                showBottomNav()
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                // Se der erro ao carregar a página, verifica se perdeu a internet
                viewLifecycleOwner.lifecycleScope.launch {
                    if (!NetworkChecker.isOnline()) {
                        showNoInternetUI()
                    }
                }
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

        // ★ NOVO: interceptação de qualquer link de /login/ do Moodle ★
        if (isMoodleLoginUrl(url)) {
            handleMoodleLoginRedirect()
            return true
        }

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

    /**
     * ★ NOVO: trata o acesso a qualquer link de "https://moodle.fei.edu.br/login/".
     * Mostra a tela de loading, desprotege os cookies, força a renovação real
     * da sessão do Moodle, reprotege os cookies e redireciona para /my/.
     */
    private fun handleMoodleLoginRedirect() {
        if (handlingMoodleLoginRedirect) return

        moodleLoginRetryCount++
        if (moodleLoginRetryCount > MAX_MOODLE_LOGIN_RETRIES) {
            Log.w(TAG, "Loop de login do Moodle detectado (${moodleLoginRetryCount} tentativas) — abortando renovação automática")
            moodleLoginRetryCount = 0
            if (isAdded) {
                hideLoadingUI()
                Toast.makeText(
                    requireContext(),
                    "Não foi possível renovar a sessão do Moodle. Tente novamente mais tarde.",
                    Toast.LENGTH_LONG
                ).show()
            }
            return
        }

        handlingMoodleLoginRedirect = true
        showLoadingUI()

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                SessionManager.desprotegerCookiesMoodle()
                isMoodleProtectionOwner = false
                // ★ Usa a renovação REAL (login por formulário), não a checagem de token ★
                val sucesso = SessionManager.forcarRenovacaoCookiesMoodle()
                if (!sucesso) {
                    Log.w(TAG, "Renovação real dos cookies do Moodle falhou")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Falha ao renovar sessão do Moodle após redirecionamento de /login/", e)
            } finally {
                if (isAdded) {
                    protegerCookiesMoodleSeNecessario()
                    webView.loadUrl(MOODLE_HOME_URL)
                }
                handlingMoodleLoginRedirect = false
            }
        }
    }

    private fun isHomeUrl(url: String?): Boolean {
        return url?.contains(HOME_URL_IDENTIFIER) == true
    }

    /** ★ NOVO: verifica se a URL pertence ao domínio do Moodle ★ */
    private fun isMoodleUrl(url: String): Boolean {
        return try {
            url.toUri().host == MOODLE_HOST
        } catch (_: Exception) {
            false
        }
    }

    /** ★ NOVO: verifica se a URL é uma página de login do Moodle ★ */
    private fun isMoodleLoginUrl(url: String): Boolean {
        return try {
            val uri = url.toUri()
            uri.host == MOODLE_HOST && (uri.path?.startsWith(MOODLE_LOGIN_PATH_PREFIX) == true)
        } catch (_: Exception) {
            false
        }
    }

    private fun protegerCookiesMoodleSeNecessario() {
        if (!isMoodleProtectionOwner) {
            SessionManager.protegerCookiesMoodle()
            isMoodleProtectionOwner = true
        }
    }

    private fun desprotegerCookiesMoodleSeNecessario() {
        if (isMoodleProtectionOwner) {
            SessionManager.desprotegerCookiesMoodle()
            isMoodleProtectionOwner = false
        }
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

    private fun showNoInternetUI() {
        if (!isAdded) return
        webView.visibility = View.GONE
        loadingContainer.visibility = View.GONE
        layoutSemInternet.visibility = View.VISIBLE
        btnTentarNovamente.setOnClickListener {
            checkConnectionAndLoad()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
    }

    override fun onDestroyView() {
        showBottomNav()
        // ★ NOVO: garante que a proteção de cookies não fique "presa" ao sair do WebView ★
        desprotegerCookiesMoodleSeNecessario()
        if (::webView.isInitialized) webView.destroy()
        super.onDestroyView()
    }
}