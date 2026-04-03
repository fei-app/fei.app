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

class EscreveVerFragment : Fragment() {

    private companion object {
        const val URL_BASE = "https://areaexclusiva.colegioetapa.com.br/escreve-etapa/minhas-redacoes"
        const val PREFS = "redacoes_prefs"
        const val KEY_CACHE = "cache_html_redacoes"
        // Seletor da tabela de redações
        const val ELEMENTO_CRITICO_SELECTOR = "div.card-header.bg-soft-blue.border-left-blue.text-blue.rounded"
    }

    private lateinit var recyclerRedacoes: RecyclerView
    private lateinit var progressBar: CircularProgressIndicator
    private lateinit var telaOffline: View
    private lateinit var txtSemRedacoes: TextView
    private lateinit var txtSemDados: TextView
    private lateinit var btnTentarNovamente: com.google.android.material.button.MaterialButton
    private lateinit var adapter: RedacoesAdapter
    private lateinit var contentLayout: View

    private var elementoCriticoPresente = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val root = inflater.inflate(R.layout.fragment_escreve_ver, container, false)

        // Inicializar views
        recyclerRedacoes = root.findViewById(R.id.recyclerRedacoes)
        progressBar = root.findViewById(R.id.progressBar)
        telaOffline = root.findViewById(R.id.telaOffline)
        txtSemRedacoes = root.findViewById(R.id.txtSemRedacoes)
        txtSemDados = root.findViewById(R.id.txtSemDados)
        btnTentarNovamente = root.findViewById(R.id.btn_tentar_novamente)
        contentLayout = root.findViewById(R.id.contentLayout)

        setupRecyclerView()

        // Botão "Tentar Novamente" volta para o HomeFragment
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

        // Verificar elemento crítico ao abrir o fragmento
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
                        Log.e("EscreveVerFragment", "Erro na conexão", e)
                        null
                    }
                }

                progressBar.visibility = View.GONE

                if (doc != null) {
                    elementoCriticoPresente = doc.selectFirst(ELEMENTO_CRITICO_SELECTOR) != null

                    if (elementoCriticoPresente) {
                        // Elemento crítico encontrado - buscar redações
                        buscarRedacoes()
                    } else {
                        // Elemento crítico não encontrado - exibir tela offline
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

    private fun buscarRedacoes() {
        if (!isOnline() || !elementoCriticoPresente) {
            exibirTelaOffline()
            return
        }

        fetchRedacoes()
    }

    private fun fetchRedacoes() {
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
                        Log.e("EscreveVerFragment", "Erro na conexão", e)
                        null
                    }
                }

                progressBar.visibility = View.GONE

                if (doc != null) {
                    // Verificar novamente o elemento crítico durante a busca
                    if (doc.selectFirst(ELEMENTO_CRITICO_SELECTOR) == null) {
                        elementoCriticoPresente = false
                        exibirTelaOffline()
                        return@launch
                    }

                    val table = doc.selectFirst(ELEMENTO_CRITICO_SELECTOR)
                    if (table != null) {
                        // Verificar se há redações
                        if (contemMensagemSemDados(table)) {
                            exibirMensagemSemRedacoes()
                        } else {
                            salvarCache(table.outerHtml())
                            parseAndDisplayTable(table)
                            exibirConteudoOnline()
                        }
                    } else {
                        exibirMensagemSemRedacoes()
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
        // Verificar se a tabela contém a mensagem de nenhuma redação
        return table.select("td.alert-danger, div.alert-danger").any { element ->
            element.text().contains("Nenhum dado encontrado", ignoreCase = true)
        }
    }

    private fun parseAndDisplayTable(table: Element) {
        val items = mutableListOf<RedacaoItem>()
        val rows = table.select("tbody > tr")

        // Ignorar a primeira linha (cabeçalho)
        for (i in 1 until rows.size) {
            val tr = rows[i]
            val cells = tr.children()
            if (cells.size < 4) continue

            // Pular linha de mensagem de erro se existir
            if (tr.selectFirst("td.alert-danger, div.alert-danger") != null) {
                continue
            }

            val tema = cells[0].text()
            val status = cells[1].text()
            val dataEnvio = cells[2].text()

            // Extrair o link do botão "Professor" na coluna de Ações (última célula)
            val link = cells[3].selectFirst("button")?.attr("onclick")?.let { onclick ->
                // Exemplo: onclick="window.location='https://areaexclusiva.colegioetapa.com.br/escreve-etapa/visualizar/631719/minhas-redacoes'"
                val regex = "window\\.location='([^']*)'".toRegex()
                regex.find(onclick)?.groupValues?.get(1)
            } ?: ""

            if (tema.isNotEmpty() && dataEnvio.isNotEmpty() && status.isNotEmpty()) {
                items.add(RedacaoItem(tema, status, dataEnvio, link))
            }
        }

        if (items.isEmpty()) {
            exibirMensagemSemRedacoes()
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
        recyclerRedacoes.layoutManager = LinearLayoutManager(requireContext())
        adapter = RedacoesAdapter(emptyList()) { redacaoItem ->
            // Verificar se a redação está pendente
            if (redacaoItem.status.contains("Pendente", ignoreCase = true)) {
                Toast.makeText(requireContext(), R.string.correcao_pendente, Toast.LENGTH_SHORT).show()
            } else {
                if (redacaoItem.link.isNotBlank()) {
                    // Cria o fragment e injeta a URL como argumento
                    val fragment = RedacaoDetalhesFragment().apply {
                        arguments = Bundle().apply {
                            putString(RedacaoDetalhesFragment.ARG_URL, redacaoItem.link)
                        }
                    }
                    // Navega para RedacaoDetalhesFragment
                    parentFragmentManager.beginTransaction()
                        .replace(R.id.nav_host_fragment, fragment)
                        .addToBackStack(null)
                        .commit()
                } else {
                    Toast.makeText(requireContext(), R.string.link_nao_disponivel, Toast.LENGTH_SHORT).show()
                }
            }
        }
        recyclerRedacoes.adapter = adapter
    }

    private fun exibirCarregando() {
        progressBar.visibility = View.VISIBLE
        recyclerRedacoes.visibility = View.GONE
        txtSemRedacoes.visibility = View.GONE
        txtSemDados.visibility = View.GONE
        telaOffline.visibility = View.GONE
    }

    private fun exibirConteudoOnline() {
        recyclerRedacoes.visibility = View.VISIBLE
        txtSemRedacoes.visibility = View.GONE
        txtSemDados.visibility = View.GONE
        telaOffline.visibility = View.GONE
        progressBar.visibility = View.GONE
    }

    private fun exibirTelaOffline() {
        telaOffline.visibility = View.VISIBLE
        recyclerRedacoes.visibility = View.GONE
        txtSemRedacoes.visibility = View.GONE
        txtSemDados.visibility = View.GONE
        progressBar.visibility = View.GONE
    }

    private fun exibirMensagemSemRedacoes() {
        recyclerRedacoes.visibility = View.GONE
        txtSemRedacoes.setText(R.string.nenhuma_redacao_encontrada)
        txtSemRedacoes.visibility = View.VISIBLE
        txtSemDados.visibility = View.GONE
        telaOffline.visibility = View.GONE
    }

    private fun exibirSemDados() {
        recyclerRedacoes.visibility = View.GONE
        txtSemRedacoes.visibility = View.GONE
        txtSemDados.visibility = View.VISIBLE
        telaOffline.visibility = View.GONE
    }

    private fun isOnline(): Boolean {
        return try {
            val connectivityManager = requireContext().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

            val network = connectivityManager.activeNetwork ?: return false
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
            return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        } catch (_: Exception) {
            false
        }
    }

    private data class RedacaoItem(
        val tema: String,
        val status: String,
        val dataEnvio: String,
        val link: String
    )

    private inner class RedacoesAdapter(
        private var items: List<RedacaoItem>,
        private val onClick: (RedacaoItem) -> Unit
    ) : RecyclerView.Adapter<RedacoesAdapter.ViewHolder>() {

        @SuppressLint("NotifyDataSetChanged")
        fun updateData(newItems: List<RedacaoItem>) {
            items = newItems
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_redacao, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.tema.text = item.tema
            holder.dataEnvio.text = getString(R.string.data_envio_format, item.dataEnvio)
            holder.status.text = item.status

            holder.card.setOnClickListener {
                onClick(item)
            }
        }

        override fun getItemCount(): Int = items.size

        inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val card: MaterialCardView = itemView.findViewById(R.id.card_redacao)
            val tema: TextView = itemView.findViewById(R.id.tema)
            val dataEnvio: TextView = itemView.findViewById(R.id.data_envio)
            val status: TextView = itemView.findViewById(R.id.status)
        }
    }
}