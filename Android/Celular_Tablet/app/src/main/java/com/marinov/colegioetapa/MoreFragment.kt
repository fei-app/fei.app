package com.marinov.colegioetapa

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.core.content.edit
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.jsoup.Jsoup
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

class MoreFragment : Fragment(), MainActivity.RefreshableFragment {

    private lateinit var ivProfilePhoto: ImageView
    private lateinit var tvStudentName: TextView
    private lateinit var tvStudentRegistration: TextView
    private lateinit var tvStudentClass: TextView
    private lateinit var tvStudentNumber: TextView
    private lateinit var btnReloadProfile: ImageView
    private lateinit var webView: WebView
    private lateinit var layoutSemInternet: LinearLayout
    private lateinit var layoutLoading: LinearLayout
    private lateinit var btnTentarNovamente: MaterialButton
    private lateinit var contentLayout: LinearLayout

    private lateinit var sharedPreferences: SharedPreferences
    private val AUTH_CHECK_URL = "https://areaexclusiva.colegioetapa.com.br/provas/notas"
    private val USER_AGENT = "Mozilla/5.0 (Linux; Android 16; sdk_gphone64_x86_64 Build/BE2A.250530.026.D1; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/133.0.6943.137 Mobile Safari/537.36"

    private var isRefreshing = false
    private val handler = Handler(Looper.getMainLooper())

    private companion object {
        private const val PROFILE_PREFS = "profile_preferences"
        private const val PROFILE_DATA_KEY = "profile_data"
        private const val PROFILE_IMAGE_KEY = "profile_image_path"
        private const val PROFILE_HAS_DATA_KEY = "profile_has_data"

        // Alpha para itens desabilitados (Material Design padrão para elementos desativados)
        private const val ALPHA_DISABLED = 0.38f
        private const val ALPHA_ENABLED = 1.0f
    }

    private fun isSafeMode(): Boolean =
        (activity as? MainActivity)?.isSafeMode() ?: true

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? = inflater.inflate(R.layout.fragment_more, container, false)

    @SuppressLint("SetJavaScriptEnabled")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        sharedPreferences = requireContext().getSharedPreferences(PROFILE_PREFS, Context.MODE_PRIVATE)

        initViews(view)
        setupClickListeners(view)

        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    (activity as? MainActivity)?.navigateToHome()
                }
            }
        )

        webView = view.findViewById(R.id.webView)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            userAgentString = USER_AGENT
        }

        checkInternetAndLoadProfile()
    }

    override fun onRefresh() {
        Log.d("MoreFragment", "Pull-to-Refresh acionado")
        isRefreshing = true
        checkInternetAndLoadProfile()
    }

    private fun stopRefreshing() {
        if (isRefreshing) {
            (activity as? MainActivity)?.setRefreshing(false)
            isRefreshing = false
        }
    }

    private fun initViews(view: View) {
        ivProfilePhoto = view.findViewById(R.id.iv_profile_photo)
        tvStudentName = view.findViewById(R.id.tv_student_name)
        tvStudentRegistration = view.findViewById(R.id.tv_student_registration)
        tvStudentClass = view.findViewById(R.id.tv_student_class)
        tvStudentNumber = view.findViewById(R.id.tv_student_number)
        btnReloadProfile = view.findViewById(R.id.btn_reload_profile)
        layoutSemInternet = view.findViewById(R.id.layout_sem_internet)
        layoutLoading = view.findViewById(R.id.layout_loading)
        btnTentarNovamente = view.findViewById(R.id.btn_tentar_novamente)
        contentLayout = view.findViewById(R.id.content_layout)
    }

    private fun setupClickListeners(view: View) {
        btnReloadProfile.setOnClickListener {
            (activity as? MainActivity)?.openCustomFragment(ProfileFragment())
        }

        btnTentarNovamente.setOnClickListener {
            (activity as? MainActivity)?.navigateToHome()
        }

        val safeMode = isSafeMode()

        // IDs que ficam bloqueados no modo de segurança
        val blockedIds = listOf(
            R.id.option_acc_detalhes,
            R.id.option_acc_inscricao,
            R.id.option_boletim_simulados,
            R.id.option_calendario_anual,
            R.id.option_food,
            R.id.option_detalhes_provas,
            R.id.option_ead_online,
            R.id.option_escreve_ver,
            R.id.option_digital,
            R.id.option_link,
            R.id.option_link_enem,
            R.id.navigation_material,
            R.id.option_plantao_duvidas,
            R.id.option_plantao_duvidas_online,
            R.id.option_provas_gabaritos,
            R.id.option_redacao_semanal,
            R.id.option_graficos
        )

        if (safeMode) {
            // Mantém os itens visíveis mas desabilita cliques e aplica transparência
            blockedIds.forEach { id ->
                view.findViewById<View>(id)?.apply {
                    isClickable = false
                    isFocusable = false
                    alpha = ALPHA_DISABLED
                }
            }
            // Perfil também fica desabilitado no safe mode
            view.findViewById<View>(R.id.profile_section)?.apply {
                isClickable = false
                isFocusable = false
                alpha = ALPHA_DISABLED
            }
            btnReloadProfile.isClickable = false
            btnReloadProfile.alpha = ALPHA_DISABLED
        } else {
            // Modo completo: garante alpha correto e configura todos os listeners
            blockedIds.forEach { id ->
                view.findViewById<View>(id)?.apply {
                    isClickable = true
                    isFocusable = true
                    alpha = ALPHA_ENABLED
                }
            }
            view.findViewById<View>(R.id.profile_section)?.apply {
                alpha = ALPHA_ENABLED
            }

            view.findViewById<View>(R.id.option_acc_detalhes).setOnClickListener {
                openWebViewWithAuthCheck("https://areaexclusiva.colegioetapa.com.br/acc/detalhes")
            }
            view.findViewById<View>(R.id.option_acc_inscricao).setOnClickListener {
                openWebViewWithAuthCheck("https://acc.colegioetapa.com.br/")
            }
            view.findViewById<View>(R.id.option_boletim_simulados).setOnClickListener {
                (activity as MainActivity).openCustomFragment(BoletimSimuladosFragment())
            }
            view.findViewById<View>(R.id.option_calendario_anual).setOnClickListener {
                openWebViewWithAuthCheck("https://areaexclusiva.colegioetapa.com.br/calendario/anual")
            }
            view.findViewById<View>(R.id.option_food).setOnClickListener {
                openWebViewWithAuthCheck("https://areaexclusiva.colegioetapa.com.br/cardapio")
            }
            view.findViewById<View>(R.id.option_detalhes_provas).setOnClickListener {
                (activity as MainActivity).openCustomFragment(DetalhesProvas())
            }
            view.findViewById<View>(R.id.option_ead_online).setOnClickListener {
                openWebViewWithAuthCheck("https://areaexclusiva.colegioetapa.com.br/ead/")
            }
            view.findViewById<View>(R.id.option_escreve_ver).setOnClickListener {
                (activity as MainActivity).openCustomFragment(EscreveVerFragment())
            }
            view.findViewById<View>(R.id.option_digital).setOnClickListener {
                openWebViewWithAuthCheck("https://areaexclusiva.colegioetapa.com.br/etapa-digital")
            }
            view.findViewById<View>(R.id.option_link).setOnClickListener {
                openWebViewWithAuthCheck("https://areaexclusiva.colegioetapa.com.br/etapa-link")
            }
            view.findViewById<View>(R.id.option_link_enem).setOnClickListener {
                openWebViewWithAuthCheck("https://areaexclusiva.colegioetapa.com.br/link-enem")
            }
            view.findViewById<View>(R.id.navigation_material).setOnClickListener {
                (activity as MainActivity).openCustomFragment(MaterialFragment())
            }
            view.findViewById<View>(R.id.option_plantao_duvidas).setOnClickListener {
                openWebViewWithAuthCheck("https://areaexclusiva.colegioetapa.com.br/horarios/plantao")
            }
            view.findViewById<View>(R.id.option_plantao_duvidas_online).setOnClickListener {
                openWebViewWithAuthCheck("https://areaexclusiva.colegioetapa.com.br/horarios/plantao/online")
            }
            view.findViewById<View>(R.id.option_provas_gabaritos).setOnClickListener {
                (activity as MainActivity).openCustomFragment(ProvasGabaritos())
            }
            view.findViewById<View>(R.id.option_redacao_semanal).setOnClickListener {
                (activity as MainActivity).openCustomFragment(RedacaoSemanalFragment())
            }
            view.findViewById<View>(R.id.option_graficos).setOnClickListener {
                (activity as MainActivity).openCustomFragment(GraficosFragment())
            }
        }

        // navigation_provas: sempre disponível com alpha total
        view.findViewById<View>(R.id.navigation_provas).apply {
            isClickable = true
            isFocusable = true
            alpha = ALPHA_ENABLED
            setOnClickListener {
                (activity as MainActivity).openCustomFragment(ProvasFragment())
            }
        }
    }

    // --- LINK NAVIGATION LOGIC ---
    private fun openWebViewWithAuthCheck(url: String) {
        if (!hasInternetConnection()) { showOfflineScreen(); return }

        showLoadingScreen()
        val authWebView = WebView(requireContext()).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.userAgentString = USER_AGENT
        }

        authWebView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, currentUrl: String?) {
                view?.evaluateJavascript(
                    "(function() { " +
                            "  var element = document.querySelector('#page-content-wrapper > div.d-lg-flex > div.container-fluid.p-3 > div.card.bg-transparent.border-0 > div.card-body.px-0.px-md-3 > div:nth-child(2) > div.card-header.bg-soft-blue.border-left-blue.text-blue.rounded.text-center');" +
                            "  if (element) {" +
                            "    return element.innerText.includes('Tabela com as Notas das Provas');" +
                            "  }" +
                            "  return false;" +
                            "})();"
                ) { value ->
                    handler.post {
                        val isAuthenticated = value == "true"
                        if (isAuthenticated) {
                            showContentScreen()
                            if (url == "https://areaexclusiva.colegioetapa.com.br/ead/" ||
                                url == "https://areaexclusiva.colegioetapa.com.br/etapa-link" ||
                                url == "https://areaexclusiva.colegioetapa.com.br/link-enem") {
                                WebViewActivity.start(requireContext(), url)
                            } else {
                                val webViewFragment = WebViewFragment().apply {
                                    arguments = WebViewFragment.createArgs(url)
                                }
                                (activity as? MainActivity)?.openCustomFragment(webViewFragment)
                            }
                        } else {
                            showOfflineScreen()
                        }
                        authWebView.destroy()
                    }
                }
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                handler.post { showOfflineScreen(); authWebView.destroy() }
            }

            override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?) {
                handler.post { showOfflineScreen(); authWebView.destroy() }
            }
        }
        authWebView.loadUrl(AUTH_CHECK_URL)
    }

    private fun showLoadingScreen() {
        contentLayout.visibility = View.GONE
        layoutSemInternet.visibility = View.GONE
        layoutLoading.visibility = View.VISIBLE
    }

    private fun showContentScreen() {
        layoutLoading.visibility = View.GONE
        layoutSemInternet.visibility = View.GONE
        contentLayout.visibility = View.VISIBLE
    }

    private fun showOfflineScreen() {
        if (isRefreshing) return
        layoutLoading.visibility = View.GONE
        contentLayout.visibility = View.GONE
        layoutSemInternet.visibility = View.VISIBLE
    }

    // --- PROFILE DATA LOGIC ---
    private fun checkInternetAndLoadProfile() {
        showContentScreen()
        loadProfileDataFromCache()

        // Em modo de segurança não tenta autenticar
        if (isSafeMode()) {
            stopRefreshing()
            return
        }

        if (!hasInternetConnection()) {
            stopRefreshing()
            return
        }
        performProfileAuthCheck()
    }

    private fun hasInternetConnection(): Boolean {
        val context = context ?: return false
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun performProfileAuthCheck() {
        val authWebView = WebView(requireContext()).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.userAgentString = USER_AGENT
        }

        authWebView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                view?.evaluateJavascript(
                    "(function() { " +
                            "  var element = document.querySelector('#page-content-wrapper > div.d-lg-flex > div.container-fluid.p-3 > div.card.bg-transparent.border-0 > div.card-body.px-0.px-md-3 > div:nth-child(2) > div.card-header.bg-soft-blue.border-left-blue.text-blue.rounded.text-center');" +
                            "  if (element) {" +
                            "    return element.innerText.includes('Tabela com as Notas das Provas');" +
                            "  }" +
                            "  return false;" +
                            "})();"
                ) { value ->
                    val isAuthenticated = value == "true"
                    if (isAuthenticated) loadProfilePage()
                    else { loadProfileDataFromCache(); stopRefreshing() }
                    authWebView.destroy()
                }
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                loadProfileDataFromCache(); stopRefreshing(); authWebView.destroy()
            }

            override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?) {
                loadProfileDataFromCache(); stopRefreshing(); authWebView.destroy()
            }
        }
        authWebView.loadUrl(AUTH_CHECK_URL)
    }

    private fun loadProfileDataFromCache() {
        try {
            val jsonString = sharedPreferences.getString(PROFILE_DATA_KEY, null)
            updateProfileViews(if (jsonString != null) JSONObject(jsonString) else JSONObject())
            loadProfileImage()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun loadProfilePage() {
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                view.evaluateJavascript(
                    """
                    (function() {
                        try {
                            const desktopData = (function() {
                                const container = document.querySelector('.popover-body .mt-2');
                                if (!container) return null;
                                const items = container.querySelectorAll('p');
                                const labels = ["Aluno", "Matrícula", "Unidade", "Período", "Sala", "Grau", "Série/Ano", "Nº chamada"];
                                const result = {};
                                items.forEach((item, index) => {
                                    if (index < labels.length) {
                                        const text = item.textContent.trim();
                                        const value = text.replace(/^[^a-zA-Z0-9]*/, '').trim();
                                        result[labels[index]] = value;
                                    }
                                });
                                return result;
                            })();
                            if (desktopData) return JSON.stringify(desktopData);

                            const mobileData = (function() {
                                const container = document.querySelector('.navbar-collapse.d-lg-none.show');
                                if (!container) return null;
                                const items = container.querySelectorAll('li span.d-block');
                                const result = {};
                                items.forEach(item => {
                                    const text = item.textContent.trim();
                                    const colonIndex = text.indexOf(':');
                                    if (colonIndex !== -1) {
                                        const label = text.substring(0, colonIndex).replace(':', '').trim();
                                        const value = text.substring(colonIndex + 1).trim();
                                        result[label] = value;
                                    }
                                });
                                return result;
                            })();
                            if (mobileData) return JSON.stringify(mobileData);

                            const fallbackData = (function() {
                                const items = document.querySelectorAll('.navbar-nav.ml-auto.mt-2.mt-lg-0.d-flex.d-lg-none > li.nav-item');
                                const result = {};
                                const labels = ["Aluno", "Matrícula", "Unidade", "Período", "Sala", "Grau", "Série/Ano", "Nº chamada"];
                                items.forEach((item, index) => {
                                    if (index < labels.length) {
                                        const text = item.textContent.trim();
                                        const colonIndex = text.indexOf(':');
                                        if (colonIndex !== -1) {
                                            const value = text.substring(colonIndex + 1).trim();
                                            result[labels[index]] = value;
                                        }
                                    }
                                });
                                return result;
                            })();
                            return JSON.stringify(fallbackData);
                        } catch (e) {
                            return JSON.stringify({ error: "JS_ERROR: " + e.message });
                        }
                    })()
                    """.trimIndent()
                ) { result -> processExtractedData(result) }
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                loadProfileDataFromCache(); stopRefreshing()
            }

            override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?) {
                loadProfileDataFromCache(); stopRefreshing()
            }
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
        webView.loadUrl("https://areaexclusiva.colegioetapa.com.br/profile")
    }

    private fun processExtractedData(rawResult: String) {
        try {
            var jsonStr = rawResult
                .removeSurrounding("\"")
                .replace("\\\"", "\"")
                .replace("\\n", "")
                .replace("\\t", "")

            if (jsonStr == "null" || jsonStr.isEmpty()) { loadProfileDataFromCache(); return }
            if (!jsonStr.startsWith("{") || !jsonStr.endsWith("}")) jsonStr = "{$jsonStr}"

            val data = JSONObject(jsonStr)
            if (data.has("error")) {
                Log.e("MoreFragment", "Erro JavaScript: ${data.getString("error")}")
                loadProfileDataFromCache()
                return
            }

            if (isValidProfileData(data)) {
                saveProfileDataOffline(data)
                updateProfileViews(data)
                fetchProfileImage()
            } else {
                loadProfileDataFromCache()
            }
        } catch (e: Exception) {
            Log.e("MoreFragment", "Erro no parsing: $rawResult", e)
            loadProfileDataFromCache()
        } finally {
            stopRefreshing()
        }
    }

    private fun isValidProfileData(data: JSONObject): Boolean =
        listOf("Aluno", "Matrícula", "Unidade").any {
            data.has(it) && data.optString(it, "").isNotBlank()
        }

    private fun saveProfileDataOffline(profileData: JSONObject) {
        try {
            sharedPreferences.edit {
                putString(PROFILE_DATA_KEY, profileData.toString())
                putBoolean(PROFILE_HAS_DATA_KEY, true)
            }
            Log.d("MoreFragment", "Dados do perfil salvos offline com sucesso")
        } catch (e: Exception) {
            Log.e("MoreFragment", "Erro ao salvar dados offline", e)
        }
    }

    @SuppressLint("SetTextI18n")
    private fun updateProfileViews(profileData: JSONObject) {
        try {
            tvStudentName.text = profileData.optString("Aluno", "Faça login para exibir os dados")
            tvStudentRegistration.text = "Matrícula: ${profileData.optString("Matrícula", "--")}"
            tvStudentClass.text = "Sala: ${profileData.optString("Sala", "--")}"
            tvStudentNumber.text = "Nº chamada: ${profileData.optString("Nº chamada", "--")}"
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun loadProfileImage() {
        val savedImagePath = sharedPreferences.getString(PROFILE_IMAGE_KEY, null)
        if (savedImagePath != null) {
            val file = File(savedImagePath)
            if (file.exists()) {
                ivProfilePhoto.setImageBitmap(BitmapFactory.decodeFile(file.absolutePath))
                return
            }
        }
        fetchProfileImage()
    }

    private fun fetchProfileImage() {
        CoroutineScope(Dispatchers.IO).launch {
            if (!hasInternetConnection()) return@launch
            try {
                val cookies = CookieManager.getInstance()
                    .getCookie("https://areaexclusiva.colegioetapa.com.br") ?: return@launch
                if (cookies.isEmpty()) return@launch
                val doc = Jsoup.connect("https://areaexclusiva.colegioetapa.com.br/profile")
                    .header("Cookie", cookies).userAgent(USER_AGENT).get()
                val imgUrl = doc.selectFirst("div.d-flex.justify-content-center img.rounded-circle")
                    ?.attr("src") ?: return@launch
                if (imgUrl.isNotEmpty()) {
                    val bitmap = downloadImage(imgUrl, cookies)
                    if (bitmap != null) {
                        val savedPath = saveImageToCache(bitmap)
                        withContext(Dispatchers.Main) { ivProfilePhoto.setImageBitmap(bitmap) }
                        if (savedPath != null) {
                            sharedPreferences.edit { putString(PROFILE_IMAGE_KEY, savedPath) }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("MoreFragment", "Erro ao carregar imagem do perfil", e)
            }
        }
    }

    private fun downloadImage(url: String, cookies: String): Bitmap? {
        return try {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.setRequestProperty("Cookie", cookies)
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.connect()
            if (connection.responseCode == HttpURLConnection.HTTP_OK)
                BitmapFactory.decodeStream(connection.inputStream)
            else null
        } catch (e: Exception) {
            Log.e("MoreFragment", "Erro ao baixar imagem", e); null
        }
    }

    private fun saveImageToCache(bitmap: Bitmap): String? {
        return try {
            val file = File(requireContext().cacheDir, "profile_image.jpg")
            FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            file.absolutePath
        } catch (e: Exception) {
            Log.e("MoreFragment", "Erro ao salvar imagem", e); null
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        handler.removeCallbacksAndMessages(null)
    }
}