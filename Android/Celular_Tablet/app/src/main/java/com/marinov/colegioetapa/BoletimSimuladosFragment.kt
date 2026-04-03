package com.marinov.colegioetapa

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
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

class BoletimSimuladosFragment : Fragment() {

    private companion object {
        const val URL_BASE = "https://areaexclusiva.colegioetapa.com.br/provas/boletins-simulados"
        const val PREFS = "boletim_prefs"
        const val KEY_CACHE = "cache_html_boletim"
        const val ELEMENTO_CRITICO_SELECTOR =
            "#page-content-wrapper > div.d-lg-flex > div.container-fluid.p-3 > div.card.bg-transparent.border-0 > div.card-body.px-0.px-md-3 > div > table"
    }

    private lateinit var recyclerBoletins: RecyclerView
    private lateinit var progressBar: CircularProgressIndicator
    private lateinit var telaOffline: View
    private lateinit var txtSemBoletins: TextView
    private lateinit var txtSemDados: TextView
    private lateinit var btnTentarNovamente: com.google.android.material.button.MaterialButton
    private lateinit var adapter: BoletimAdapter
    private lateinit var contentLayout: View

    private var elementoCriticoPresente = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val root = inflater.inflate(R.layout.fragment_boletim_simulados, container, false)

        recyclerBoletins = root.findViewById(R.id.recyclerBoletins)
        progressBar = root.findViewById(R.id.progressBar)
        telaOffline = root.findViewById(R.id.telaOffline)
        txtSemBoletins = root.findViewById(R.id.txtSemBoletins)
        txtSemDados = root.findViewById(R.id.txtSemDados)
        btnTentarNovamente = root.findViewById(R.id.btn_tentar_novamente)
        contentLayout = root.findViewById(R.id.contentLayout)

        setupRecyclerView()

        btnTentarNovamente.setOnClickListener {
            navigateToHomeFragment()
        }

        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val callback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                navigateToHomeFragment()
            }
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, callback)

        verificarElementoCritico()
    }

    private fun navigateToHomeFragment() {
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
                        Log.e("BoletimFragment", "Erro na conexão", e)
                        null
                    }
                }

                progressBar.visibility = View.GONE

                if (doc != null) {
                    elementoCriticoPresente = doc.selectFirst(ELEMENTO_CRITICO_SELECTOR) != null

                    if (elementoCriticoPresente) {
                        buscarBoletins()
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

    private fun buscarBoletins() {
        if (!isOnline() || !elementoCriticoPresente) {
            exibirTelaOffline()
            return
        }

        fetchBoletins()
    }

    private fun fetchBoletins() {
        CoroutineScope(Dispatchers.Main).launch {
            try {
                exibirCarregando()

                val doc = withContext(Dispatchers.IO) {
                    try {
                        val cookieHeader = CookieManager.getInstance().getCookie(URL_BASE)
                        Jsoup.connect(URL_BASE)
                            .header("Cookie", cookieHeader)
                            .userAgent("Mozilla/5.0 (Linux; Android 16; sdk_gphone64_x86_64 Build/BE2A.250530.026.D1; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/133.0.6943.137 Mobile Safari/537.36")
                            .timeout(15000)
                            .get()
                    } catch (e: Exception) {
                        Log.e("BoletimFragment", "Erro na conexão", e)
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
                            exibirMensagemSemBoletins()
                        } else {
                            salvarCache(table.outerHtml())
                            parseAndDisplayTable(table)
                            exibirConteudoOnline()
                        }
                    } else {
                        exibirMensagemSemBoletins()
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
        val items = mutableListOf<BoletimItem>()
        val rows = table.select("tbody > tr")

        for (row in rows) {
            val cells = row.select("td, th")
            if (cells.size < 7) continue

            if (row.selectFirst("td.alert-danger, div.alert-danger") != null) {
                continue
            }

            val data = cells[0].text().trim()
            val prova = cells[1].text().trim()
            val acertos = cells[2].text().trim()
            val aproveitamento = cells[3].text().trim()
            val link = row.select("a").attr("href") ?: ""

            if (data.isNotEmpty() && prova.isNotEmpty()) {
                items.add(BoletimItem(prova, data, acertos, aproveitamento, link))
            }
        }

        if (items.isEmpty()) {
            exibirMensagemSemBoletins()
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
        recyclerBoletins.layoutManager = LinearLayoutManager(requireContext())
        adapter = BoletimAdapter(emptyList()) { boletimItem ->
            if (boletimItem.link.isNotBlank()) {
                abrirWebViewFragment(boletimItem.link)
            } else {
                Toast.makeText(requireContext(), "Link não disponível", Toast.LENGTH_SHORT).show()
            }
        }
        recyclerBoletins.adapter = adapter
    }

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
        recyclerBoletins.visibility = View.GONE
        txtSemBoletins.visibility = View.GONE
        txtSemDados.visibility = View.GONE
        telaOffline.visibility = View.GONE
    }

    private fun exibirConteudoOnline() {
        recyclerBoletins.visibility = View.VISIBLE
        txtSemBoletins.visibility = View.GONE
        txtSemDados.visibility = View.GONE
        telaOffline.visibility = View.GONE
        progressBar.visibility = View.GONE
    }

    private fun exibirTelaOffline() {
        telaOffline.visibility = View.VISIBLE
        recyclerBoletins.visibility = View.GONE
        txtSemBoletins.visibility = View.GONE
        txtSemDados.visibility = View.GONE
        progressBar.visibility = View.GONE
    }

    @SuppressLint("SetTextI18n")
    private fun exibirMensagemSemBoletins() {
        recyclerBoletins.visibility = View.GONE
        txtSemBoletins.text = "Nenhum boletim disponível."
        txtSemBoletins.visibility = View.VISIBLE
        txtSemDados.visibility = View.GONE
        telaOffline.visibility = View.GONE
    }

    private fun exibirSemDados() {
        recyclerBoletins.visibility = View.GONE
        txtSemBoletins.visibility = View.GONE
        txtSemDados.visibility = View.VISIBLE
        telaOffline.visibility = View.GONE
    }

    private fun isOnline(): Boolean {
        return try {
            val connectivityManager = requireContext().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = connectivityManager.activeNetwork ?: return false
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (_: Exception) {
            false
        }
    }

    private data class BoletimItem(
        val prova: String,
        val data: String,
        val acertos: String,
        val aproveitamento: String,
        val link: String
    )

    private class BoletimAdapter(
        private var items: List<BoletimItem>,
        private val onClick: (BoletimItem) -> Unit
    ) : RecyclerView.Adapter<BoletimAdapter.ViewHolder>() {

        @SuppressLint("NotifyDataSetChanged")
        fun updateData(newItems: List<BoletimItem>) {
            items = newItems
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_boletim_simulado, parent, false)
            return ViewHolder(view)
        }

        @SuppressLint("SetTextI18n")
        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.prova.text = item.prova
            holder.data.text = item.data
            holder.acertos.text = item.acertos
            holder.aproveitamento.text = "${item.aproveitamento} de aproveitamento"

            holder.card.setOnClickListener {
                onClick(item)
            }
        }

        override fun getItemCount(): Int = items.size

        class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val card: MaterialCardView = itemView.findViewById(R.id.card_boletim)
            val prova: TextView = itemView.findViewById(R.id.txtProva)
            val data: TextView = itemView.findViewById(R.id.txtData)
            val acertos: TextView = itemView.findViewById(R.id.txtAcertos)
            val aproveitamento: TextView = itemView.findViewById(R.id.txtAproveitamento)
        }
    }
}