package com.marinov.colegioetapa

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.net.ConnectivityManager
import android.net.NetworkInfo
import android.os.Bundle
import android.text.Html
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.Fragment
import com.google.android.material.card.MaterialCardView
import com.google.android.material.progressindicator.CircularProgressIndicator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

class RedacaoSemanalFragment : Fragment() {

    private companion object {
        const val URL_BASE = "https://areaexclusiva.colegioetapa.com.br/redacao"
        const val PREFS = "redacao_semanal_prefs"
        const val KEY_CACHE = "cache_html_redacao_semanal"
        // Seletores fornecidos
        const val TITULO_SELECTOR = "#page-content-wrapper > div.d-lg-flex > div.container-fluid.p-3 > div.card.bg-transparent.border-0 > div.card-body.px-0.px-md-3 > div > div.col-lg-9 > div > div.card-header.bg-soft-blue.border-left-blue.text-blue.rounded"
        const val SUGESTOES_SELECTOR = "#page-content-wrapper > div.d-lg-flex > div.container-fluid.p-3 > div.card.bg-transparent.border-0 > div.card-body.px-0.px-md-3 > div > div.col-lg-3.mb-3"
        const val PROPOSTA_SELECTOR = "#page-content-wrapper > div.d-lg-flex > div.container-fluid.p-3 > div.card.bg-transparent.border-0 > div.card-body.px-0.px-md-3 > div > div.col-lg-9 > div > div.card-body.px-0.px-lg-3.overflow-auto"
        // Elemento crítico: a proposta de redação
        const val ELEMENTO_CRITICO_SELECTOR = TITULO_SELECTOR
    }

    private lateinit var progressBar: CircularProgressIndicator
    private lateinit var telaOffline: View
    private lateinit var txtSemDados: TextView
    private lateinit var btnTentarNovamente: com.google.android.material.button.MaterialButton
    private lateinit var contentLayout: View
    private lateinit var webViewProposta: WebView
    private lateinit var cardSugestoes: MaterialCardView
    private lateinit var txtTitulo: TextView
    private lateinit var txtSugestoesTitulo: TextView
    private lateinit var txtSugestoesConteudo: TextView

    private var elementoCriticoPresente = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val root = inflater.inflate(R.layout.fragment_redacao_semanal, container, false)

        progressBar = root.findViewById(R.id.progressBar)
        telaOffline = root.findViewById(R.id.telaOffline)
        txtSemDados = root.findViewById(R.id.txtSemDados)
        btnTentarNovamente = root.findViewById(R.id.btn_tentar_novamente)
        contentLayout = root.findViewById(R.id.contentLayout)
        webViewProposta = root.findViewById(R.id.webViewProposta)
        cardSugestoes = root.findViewById(R.id.cardSugestoes)
        txtTitulo = root.findViewById(R.id.txtTitulo)
        txtSugestoesTitulo = root.findViewById(R.id.txtSugestoesTitulo)
        txtSugestoesConteudo = root.findViewById(R.id.txtSugestoesConteudo)

        btnTentarNovamente.setOnClickListener {
            voltarParaHome()
        }

        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val callback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                voltarParaHome()
            }
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, callback)

        verificarElementoCritico()
    }

    private fun voltarParaHome() {
        (activity as? MainActivity)?.navigateToHome()
    }

    private fun verificarElementoCritico() {
        if (!isOnline()) {
            exibirTelaOffline()
            return
        }

        CoroutineScope(Dispatchers.Main).launch {
            exibirCarregando()

            try {
                val doc = withContext(Dispatchers.IO) {
                    try {
                        val cookieHeader = CookieManager.getInstance().getCookie(URL_BASE)
                        Jsoup.connect(URL_BASE)
                            .header("Cookie", cookieHeader)
                            .userAgent("Mozilla/5.0 (Linux; Android 16; sdk_gphone64_x86_64 Build/BE2A.250530.026.D1; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/133.0.6943.137 Mobile Safari/537.36")
                            .timeout(15000)
                            .get()
                    } catch (e: Exception) {
                        Log.e("RedacaoSemanal", "Erro na conexão", e)
                        null
                    }
                }

                progressBar.visibility = View.GONE

                if (doc != null) {
                    elementoCriticoPresente = doc.selectFirst(ELEMENTO_CRITICO_SELECTOR) != null

                    if (elementoCriticoPresente) {
                        buscarRedacaoSemanal(doc)
                    } else {
                        exibirTelaOffline()
                    }
                } else {
                    exibirTelaOffline()
                }
            } catch (_: Exception) {
                progressBar.visibility = View.GONE
                exibirTelaOffline()
            }
        }
    }

    private fun buscarRedacaoSemanal(doc: Document? = null) {
        if (!isOnline() || !elementoCriticoPresente) {
            exibirTelaOffline()
            return
        }

        if (doc != null) {
            processarDocumento(doc)
        } else {
            fetchRedacaoSemanal(URL_BASE)
        }
    }

    private fun fetchRedacaoSemanal(url: String) {
        CoroutineScope(Dispatchers.Main).launch {
            try {
                exibirCarregando()

                val doc = withContext(Dispatchers.IO) {
                    try {
                        val cookieHeader = CookieManager.getInstance().getCookie(url)
                        Jsoup.connect(url)
                            .header("Cookie", cookieHeader)
                            .userAgent("Mozilla/5.0 (Linux; Android 16; sdk_gphone64_x86_64 Build/BE2A.250530.026.D1; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/133.0.6943.137 Mobile Safari/537.36")
                            .timeout(15000)
                            .get()
                    } catch (e: Exception) {
                        Log.e("RedacaoSemanal", "Erro na conexão", e)
                        null
                    }
                }

                progressBar.visibility = View.GONE

                if (doc != null) {
                    if (doc.selectFirst(ELEMENTO_CRITICO_SELECTOR) == null) {
                        elementoCriticoPresente = false
                        exibirTelaOffline()
                        return@launch
                    }

                    processarDocumento(doc)
                    salvarCache(doc.outerHtml())
                } else {
                    carregarCache()
                }
            } catch (_: Exception) {
                progressBar.visibility = View.GONE
                carregarCache()
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun processarDocumento(doc: Document) {
        // Extrair título
        val tituloElement = doc.selectFirst(TITULO_SELECTOR)
        val titulo = tituloElement?.text() ?: ""

        // Extrair sugestões
        val sugestoesElement = doc.selectFirst(SUGESTOES_SELECTOR)
        val sugestoesTitulo = sugestoesElement?.selectFirst(".card-header")?.text() ?: "Sugestões importantes"
        val sugestoesConteudo = sugestoesElement?.selectFirst(".card-body")?.html() ?: ""

        // Extrair proposta
        val propostaElement = doc.selectFirst(PROPOSTA_SELECTOR)
        val propostaHtml = propostaElement?.html() ?: ""

        // Atualizar UI
        txtTitulo.text = titulo
        txtSugestoesTitulo.text = sugestoesTitulo
        txtSugestoesConteudo.text =
            Html.fromHtml(sugestoesConteudo, Html.FROM_HTML_MODE_COMPACT)

        // Configurar WebView para a proposta com melhor legibilidade
        webViewProposta.settings.javaScriptEnabled = true
        webViewProposta.settings.domStorageEnabled = true
        webViewProposta.settings.loadWithOverviewMode = true
        webViewProposta.settings.useWideViewPort = true
        webViewProposta.settings.builtInZoomControls = true
        webViewProposta.settings.displayZoomControls = false
        webViewProposta.settings.setSupportZoom(true)

        // Verificar se o tema escuro está ativo
        val isDarkTheme = when (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) {
            Configuration.UI_MODE_NIGHT_YES -> true
            else -> false
        }

        // Definir cores fixas
        val backgroundColor = if (isDarkTheme) "#000000" else "#FFFFFF"
        val textColor = if (isDarkTheme) "#FFFFFF" else "#000000"

        // CSS para melhorar legibilidade e adaptar ao tema
        val css = """
        <style type='text/css'>
            body {
                color: $textColor !important;
                background-color: $backgroundColor !important;
                font-size: 18px !important;
                line-height: 1.6 !important;
                padding: 12px !important;
                font-family: 'Roboto', sans-serif !important;
            }
            
            * {
                color: $textColor !important;
                background-color: transparent !important;
            }
            
            img {
                max-width: 100% !important;
                height: auto !important;
                background-color: transparent !important;
            }
            
            p, li, h1, h2, h3, h4, h5, h6 {
                color: inherit !important;
            }
            
            table {
                width: 100% !important;
                background-color: transparent !important;
            }
            
            td, th {
                word-wrap: break-word;
                background-color: transparent !important;
                color: inherit !important;
            }
        </style>
    """.trimIndent()

        // HTML final com melhorias de estilo
        val styledHtml = """
        <!DOCTYPE html>
        <html>
        <head>
            <meta name="viewport" content="width=device-width, initial-scale=1.0">
            $css
        </head>
        <body>
            $propostaHtml
        </body>
        </html>
    """.trimIndent()

        webViewProposta.loadDataWithBaseURL(URL_BASE, styledHtml, "text/html", "UTF-8", null)

        exibirConteudoOnline()
    }

    private fun salvarCache(html: String) {
        requireContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_CACHE, html)
            .apply()
    }

    private fun carregarCache() {
        val html = requireContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_CACHE, null)

        if (html != null) {
            val doc = Jsoup.parse(html)
            if (doc.selectFirst(ELEMENTO_CRITICO_SELECTOR) != null) {
                processarDocumento(doc)
                return
            }
        }
        exibirSemDados()
    }

    private fun exibirCarregando() {
        progressBar.visibility = View.VISIBLE
        contentLayout.visibility = View.GONE
        txtSemDados.visibility = View.GONE
        telaOffline.visibility = View.GONE
    }

    private fun exibirConteudoOnline() {
        contentLayout.visibility = View.VISIBLE
        progressBar.visibility = View.GONE
        txtSemDados.visibility = View.GONE
        telaOffline.visibility = View.GONE
    }

    private fun exibirTelaOffline() {
        telaOffline.visibility = View.VISIBLE
        contentLayout.visibility = View.GONE
        txtSemDados.visibility = View.GONE
        progressBar.visibility = View.GONE
    }

    private fun exibirSemDados() {
        txtSemDados.visibility = View.VISIBLE
        contentLayout.visibility = View.GONE
        telaOffline.visibility = View.GONE
        progressBar.visibility = View.GONE
    }

    private fun isOnline(): Boolean {
        return try {
            val cm = requireContext().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val netInfo: NetworkInfo? = cm.activeNetworkInfo
            netInfo != null && netInfo.isConnected
        } catch (_: Exception) {
            false
        }
    }
}