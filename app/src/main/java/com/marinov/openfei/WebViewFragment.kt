package com.marinov.openfei

import android.annotation.SuppressLint
import android.app.Activity
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
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
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton

class WebViewFragment : Fragment() {
    private lateinit var webView: WebView
    private lateinit var layoutSemInternet: LinearLayout
    private lateinit var btnTentarNovamente: MaterialButton

    // Variável para gerenciar o callback de upload de arquivos
    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    // Launcher para o seletor de arquivos (Upload)
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
        private const val HOME_URL_IDENTIFIER = "https://interage.fei.org.br/secureserver/portal/graduacao/home"

        @JvmStatic
        fun createArgs(url: String): Bundle = Bundle().apply { putString(ARG_URL, url) }
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
        val callback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) webView.goBack()
                else requireActivity().supportFragmentManager.popBackStack()
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

        webView.webViewClient = object : WebViewClient() {
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

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                showWebViewWithAnimation(view)
                layoutSemInternet.visibility = View.GONE
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (!isOnline()) showNoInternetUI()
            }
        }

        // Suporte a Download
        webView.setDownloadListener { url, userAgent, contentDisposition, mimetype, _ ->
            try {
                val request = DownloadManager.Request(Uri.parse(url))
                request.setMimeType(mimetype)

                // Repassar Cookies de sessão
                val cookies = CookieManager.getInstance().getCookie(url)
                request.addRequestHeader("cookie", cookies)
                request.addRequestHeader("User-Agent", userAgent)

                request.setDescription("Baixando arquivo...")
                val fileName = URLUtil.guessFileName(url, contentDisposition, mimetype)
                request.setTitle(fileName)

                request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)

                val dm = requireContext().getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                dm.enqueue(request)

                Toast.makeText(requireContext(), "Download iniciado", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Erro ao iniciar download", Toast.LENGTH_SHORT).show()
            }
        }

        arguments?.getString(ARG_URL)?.let { webView.loadUrl(it) }

        webView.webChromeClient = object : WebChromeClient() {
            // Suporte para Upload de Arquivos
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
                } catch (e: ActivityNotFoundException) {
                    this@WebViewFragment.filePathCallback = null
                    Toast.makeText(requireContext(), "Nenhum aplicativo para selecionar arquivos encontrado.", Toast.LENGTH_LONG).show()
                    return false
                }
                return true
            }
        }
    }

    private fun handleUrlOverride(url: String?): Boolean {
        if (url == null) return false

        if (isHomeUrl(url)) {
            Handler(Looper.getMainLooper()).post {
                (activity as? MainActivity)?.navigateToHome()
            }
            return true // Interceptado
        }

        val uri = Uri.parse(url)
        val host = uri.host ?: return false

        // Verifica se é domínio da FEI
        if (host.endsWith("fei.edu.br") || host.endsWith("fei.org.br")) {
            return false // Deixa o WebView carregar internamente
        }

        // Caso contrário, abre no navegador padrão
        try {
            val intent = Intent(Intent.ACTION_VIEW, uri)
            requireContext().startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "Nenhum navegador encontrado.", Toast.LENGTH_SHORT).show()
        }
        return true // Interceptado
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
                Toast.makeText(requireContext(), "Sem conexão com a internet", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
    }

    override fun onDestroyView() {
        if (::webView.isInitialized) webView.destroy()
        super.onDestroyView()
    }
}