package com.marinov.openfei.ui.webview

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.behavior.HideBottomViewOnScrollBehavior
import com.google.android.material.button.MaterialButton
import com.marinov.openfei.R
import com.marinov.openfei.data.NetworkChecker
import com.marinov.openfei.ui.main.MainActivity
import kotlinx.coroutines.launch

class WebViewFragment : Fragment() {

    private lateinit var webView: WebView
    private lateinit var layoutSemInternet: LinearLayout
    private lateinit var btnTentarNovamente: MaterialButton
    private lateinit var loadingContainer: FrameLayout
    private lateinit var navigationController: WebViewNavigationController

    private val fileChooserHelper = WebViewFileChooserHelper(this) { requireContext() }

    private var bottomNavContainer: View? = null
    private var bottomNavBehavior: HideBottomViewOnScrollBehavior<View>? = null

    companion object {
        private const val ARG_URL = "url"
        private const val ARG_EXIT_TO_HOME = "exit_to_home"

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

        navigationController = WebViewNavigationController(
            context = requireContext(),
            webView = webView,
            scope = viewLifecycleOwner.lifecycleScope,
            ui = object : WebViewNavigationController.UiCallbacks {
                override fun showLoading() {
                    showLoadingUI()
                }

                override fun hideLoading() {
                    hideLoadingUI()
                }
            },
            navigation = object : WebViewNavigationController.NavigationCallbacks {
                override fun shouldInterceptHome(): Boolean = true

                override fun navigateHome() {
                    (activity as? MainActivity)?.navigateToHome()
                }

                override fun openLogin() {
                    WebViewSessionHelper.openLoginAndFinish(requireActivity())
                }

                override fun isAlive(): Boolean = isAdded && this@WebViewFragment.view != null
            }
        )

        checkConnectionAndLoad()
    }

    private fun checkConnectionAndLoad() {
        viewLifecycleOwner.lifecycleScope.launch {
            showLoadingUI()

            when (WebViewSessionHelper.checkSession()) {
                is WebViewSessionHelper.SessionState.OnlineOk -> {
                    // Importante: não ocultar o loading aqui.
                    // Ele só deve sumir quando a página terminar de carregar.
                    initializeWebView()
                }

                is WebViewSessionHelper.SessionState.Offline -> {
                    hideLoadingUI()
                    showNoInternetUI()
                }

                is WebViewSessionHelper.SessionState.LoginNeeded -> {
                    hideLoadingUI()
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.sessao_expirada_login),
                        Toast.LENGTH_LONG
                    ).show()
                    WebViewSessionHelper.openLoginAndFinish(requireActivity())
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

        WebViewConfig.configure(webView)
        WebViewConfig.setupDownloadListener(webView, requireContext(), showToast = true)
        fileChooserHelper.attachTo(webView)
        setupBottomNavAutoHide()

        webView.visibility = View.INVISIBLE

        webView.webViewClient = @SuppressLint("MissingOnRenderProcessGone")
        object : WebViewClient() {

            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                val headers = request?.requestHeaders?.toMutableMap()
                headers?.remove("X-Requested-With")
                return super.shouldInterceptRequest(view, request)
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                return navigationController.handleUrl(request?.url.toString())
            }

            @Deprecated("Deprecated in Java")
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                return navigationController.handleUrl(url)
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)

                // Sempre que iniciar uma nova navegação, volta a exibir o loading
                showLoadingUI()

                navigationController.onPageStarted(url)
                showBottomNav()
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)

                // Loading só deve ser ocultado quando a página terminar de carregar
                hideLoadingUI()

                WebViewConfig.disableTransitions(view)
                layoutSemInternet.visibility = View.GONE
                showBottomNav()
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError
            ) {
                viewLifecycleOwner.lifecycleScope.launch {
                    if (!NetworkChecker.isOnline()) {
                        showNoInternetUI()
                    }
                }
            }
        }

        arguments?.getString(ARG_URL)?.let { webView.loadUrl(it) }
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

    private fun showNoInternetUI() {
        if (!isAdded) return
        webView.visibility = View.GONE
        loadingContainer.visibility = View.GONE
        layoutSemInternet.visibility = View.VISIBLE
        btnTentarNovamente.setOnClickListener {
            checkConnectionAndLoad()
        }
    }

    override fun onDestroyView() {
        showBottomNav()

        if (::navigationController.isInitialized) {
            navigationController.onCleared()
        }

        if (::webView.isInitialized) {
            webView.destroy()
        }

        super.onDestroyView()
    }
}