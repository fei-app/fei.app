// Arquivo: GraficosFragment.kt
package com.marinov.colegioetapa

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkInfo
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.core.content.edit
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.google.android.material.progressindicator.CircularProgressIndicator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class GraficosFragment : Fragment() {

    private companion object {
        const val URL_BASE = "https://areaexclusiva.colegioetapa.com.br/provas/relatorio-evolucao"
        const val PREFS = "graficos_prefs"
        const val KEY_CACHE = "cache_html_graficos"
        const val ELEMENTO_CRITICO_SELECTOR =
            "#page-content-wrapper > div.d-lg-flex > div.container-fluid.p-3 > div.card.bg-transparent.border-0 > div.card-body.px-0.px-md-3 > div > table"
    }

    private lateinit var recyclerGraficos: RecyclerView
    private lateinit var progressBar: CircularProgressIndicator
    private lateinit var telaOffline: View
    private lateinit var txtSemGraficos: TextView
    private lateinit var txtSemDados: TextView
    private lateinit var btnTentarNovamente: com.google.android.material.button.MaterialButton
    private lateinit var adapter: GraficosAdapter
    private lateinit var contentLayout: View

    private var elementoCriticoPresente = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val root = inflater.inflate(R.layout.fragment_graficos, container, false)

        recyclerGraficos = root.findViewById(R.id.recyclerGraficos)
        progressBar = root.findViewById(R.id.progressBar)
        telaOffline = root.findViewById(R.id.telaOffline)
        txtSemGraficos = root.findViewById(R.id.txtSemGraficos)
        txtSemDados = root.findViewById(R.id.txtSemDados)
        btnTentarNovamente = root.findViewById(R.id.btn_tentar_novamente)
        contentLayout = root.findViewById(R.id.contentLayout)

        setupRecyclerView()

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
                        Log.e("GraficosFragment", "Erro na conexão", e)
                        null
                    }
                }

                progressBar.visibility = View.GONE

                if (doc != null) {
                    elementoCriticoPresente = doc.selectFirst(ELEMENTO_CRITICO_SELECTOR) != null

                    if (elementoCriticoPresente) {
                        buscarGraficos()
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

    private fun buscarGraficos() {
        if (!isOnline() || !elementoCriticoPresente) {
            exibirTelaOffline()
            return
        }

        fetchGraficos(URL_BASE)
    }

    private fun fetchGraficos(url: String) {
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
                        Log.e("GraficosFragment", "Erro na conexão", e)
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

                    val table = doc.selectFirst(ELEMENTO_CRITICO_SELECTOR)
                    if (table != null) {
                        if (contemMensagemSemDados(table)) {
                            exibirMensagemSemGraficos()
                        } else {
                            salvarCache(table.outerHtml())
                            parseAndDisplayTable(table)
                            exibirConteudoOnline()
                        }
                    } else {
                        exibirMensagemSemGraficos()
                    }
                } else {
                    carregarCache()
                }
            } catch (_: Exception) {
                progressBar.visibility = View.GONE
                carregarCache()
            }
        }
    }

    private fun contemMensagemSemDados(table: Element): Boolean {
        return table.select("td.alert-danger, div.alert-danger").any { element ->
            element.text().contains("Nenhum dado encontrado", ignoreCase = true)
        }
    }

    private fun parseAndDisplayTable(table: Element) {
        val items = mutableListOf<GraficoItem>()
        val rows = table.select("tbody > tr")

        for (row in rows) {
            val cells = row.select("td")
            if (cells.size < 3) continue

            if (row.selectFirst("td.alert-danger, div.alert-danger") != null) {
                continue
            }

            val vestibular = cells[0].text()
            val numProvas = cells[1].text()
            val link = cells[2].selectFirst("a")?.attr("href") ?: ""

            if (vestibular.isNotEmpty() && numProvas.isNotEmpty()) {
                items.add(GraficoItem(vestibular, numProvas, link))
            }
        }

        if (items.isEmpty()) {
            exibirMensagemSemGraficos()
        } else {
            adapter.updateData(items)
            exibirConteudoOnline()
        }
    }

    private fun salvarCache(html: String) {
        requireContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putString(KEY_CACHE, html)
        }
    }

    private fun carregarCache() {
        val html = requireContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_CACHE, null)

        if (html != null) {
            val table = Jsoup.parse(html).selectFirst(ELEMENTO_CRITICO_SELECTOR)
            if (table != null) {
                parseAndDisplayTable(table)
                return
            }
        }
        exibirSemDados()
    }

    private fun setupRecyclerView() {
        recyclerGraficos.layoutManager = LinearLayoutManager(requireContext())
        adapter = GraficosAdapter(emptyList()) { graficoItem ->
            if (graficoItem.link.isNotBlank()) {
                abrirWebViewFragment(graficoItem.link)
            } else {
                Toast.makeText(requireContext(), "Link não disponível", Toast.LENGTH_SHORT).show()
            }
        }
        recyclerGraficos.adapter = adapter
    }

    /**
     * Navega para WebViewFragment, passando a URL do vestibular via argumento.
     * Substitui o container principal (nav_host_fragment) pelo novo fragment.
     */
    private fun abrirWebViewFragment(url: String) {
        val webViewFragment = WebViewFragment().apply {
            arguments = WebViewFragment.createArgs(url)
        }
        requireActivity().supportFragmentManager.beginTransaction()
            .replace(R.id.nav_host_fragment, webViewFragment)
            .addToBackStack(null)
            .commit()
    }

    private fun exibirCarregando() {
        progressBar.visibility = View.VISIBLE
        recyclerGraficos.visibility = View.GONE
        txtSemGraficos.visibility = View.GONE
        txtSemDados.visibility = View.GONE
        telaOffline.visibility = View.GONE
    }

    private fun exibirConteudoOnline() {
        recyclerGraficos.visibility = View.VISIBLE
        txtSemGraficos.visibility = View.GONE
        txtSemDados.visibility = View.GONE
        telaOffline.visibility = View.GONE
        progressBar.visibility = View.GONE
    }

    private fun exibirTelaOffline() {
        telaOffline.visibility = View.VISIBLE
        recyclerGraficos.visibility = View.GONE
        txtSemGraficos.visibility = View.GONE
        txtSemDados.visibility = View.GONE
        progressBar.visibility = View.GONE
    }

    @SuppressLint("SetTextI18n")
    private fun exibirMensagemSemGraficos() {
        recyclerGraficos.visibility = View.GONE
        txtSemGraficos.text = "Nenhum relatório encontrado."
        txtSemGraficos.visibility = View.VISIBLE
        txtSemDados.visibility = View.GONE
        telaOffline.visibility = View.GONE
    }

    private fun exibirSemDados() {
        recyclerGraficos.visibility = View.GONE
        txtSemGraficos.visibility = View.GONE
        txtSemDados.visibility = View.VISIBLE
        telaOffline.visibility = View.GONE
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

    private data class GraficoItem(
        val vestibular: String,
        val numProvas: String,
        val link: String
    )

    private class GraficosAdapter(
        private var items: List<GraficoItem>,
        private val onClick: (GraficoItem) -> Unit
    ) : RecyclerView.Adapter<GraficosAdapter.ViewHolder>() {

        @SuppressLint("NotifyDataSetChanged")
        fun updateData(newItems: List<GraficoItem>) {
            items = newItems
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_grafico, parent, false)
            return ViewHolder(view)
        }

        @SuppressLint("SetTextI18n")
        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.vestibular.text = item.vestibular
            holder.numProvas.text = "Provas: ${item.numProvas}"

            holder.card.setOnClickListener {
                onClick(item)
            }
        }

        override fun getItemCount(): Int = items.size

        class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val card: MaterialCardView = itemView.findViewById(R.id.card_grafico)
            val vestibular: TextView = itemView.findViewById(R.id.vestibular)
            val numProvas: TextView = itemView.findViewById(R.id.num_provas)
        }
    }
}