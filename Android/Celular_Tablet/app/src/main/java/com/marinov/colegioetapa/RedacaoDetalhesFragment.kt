package com.marinov.colegioetapa

import android.content.Context
import android.graphics.Typeface
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.core.content.edit
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.android.material.progressindicator.CircularProgressIndicator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class RedacaoDetalhesFragment : Fragment() {

    companion object {
        const val ARG_URL = "url"
        const val PREFS = "redacao_detalhes_prefs"
        const val KEY_CACHE = "cache_html_redacao_detalhes"
        // Seletores
        const val TITULO_SELECTOR = "#page-content-wrapper > div.d-lg-flex > div.container-fluid.p-3 > div.card.bg-transparent.border-0 > div.card-body.px-0.px-md-3 > div > div.card-header.bg-soft-blue.border-left-blue.text-blue.rounded > div > span.d-none.d-sm-inline-block"
        const val ALERTA_PENDENTE_SELECTOR = "div.alert.alert-info"
        const val TABELA_INFO_SELECTOR = "#page-content-wrapper > div.d-lg-flex > div.container-fluid.p-3 > div.card.bg-transparent.border-0 > div.card-body.px-0.px-md-3 > div > div.card-body > div:nth-child(1) > table"
        const val TABELA_AVALIACAO_SELECTOR = "#page-content-wrapper > div.d-lg-flex > div.container-fluid.p-3 > div.card.bg-transparent.border-0 > div.card-body.px-0.px-md-3 > div > div.card-body > div:nth-child(2) > table"
        const val COMENTARIO_GERAL_SELECTOR = "#page-content-wrapper > div.d-lg-flex > div.container-fluid.p-3 > div.card.bg-transparent.border-0 > div.card-body.px-0.px-md-3 > div > div.card-body > div:nth-child(3) > div.card-body"
        const val IMAGEM_REDACAO_SELECTOR = "#box-guide > img"
        const val COMENTARIOS_ESPECIFICOS_SELECTOR = "#page-content-wrapper > div.d-lg-flex > div.container-fluid.p-3 > div.card.bg-transparent.border-0 > div.card-body.px-0.px-md-3 > div > div.card-body > div.d-flex.flex-column.flex-lg-row.mt-3.justify-content-between.w-100 > div.col-lg-3.overflow-auto.mt-3.mt-lg-0.vh-100 > div"
    }

    private lateinit var progressBar: CircularProgressIndicator
    private lateinit var telaOffline: View
    private lateinit var btnTentarNovamente: com.google.android.material.button.MaterialButton
    private lateinit var contentLayout: View
    private lateinit var tema: TextView
    private lateinit var recyclerTabelaInfo: RecyclerView
    private lateinit var recyclerTabelaAvaliacao: RecyclerView
    private lateinit var comentarioGeral: TextView
    private lateinit var imagemRedacao: android.widget.ImageView
    private lateinit var recyclerComentarios: RecyclerView
    private lateinit var txtSemDados: TextView
    private lateinit var txtPendente: TextView
    private lateinit var secaoDetalhes: View

    private var elementoCriticoPresente = false
    private var url: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            url = it.getString(ARG_URL) ?: ""
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val root = inflater.inflate(R.layout.verredacao, container, false)

        progressBar = root.findViewById(R.id.progressBar)
        telaOffline = root.findViewById(R.id.telaOffline)
        btnTentarNovamente = root.findViewById(R.id.btn_tentar_novamente)
        contentLayout = root.findViewById(R.id.contentLayout)
        tema = root.findViewById(R.id.tema)
        recyclerTabelaInfo = root.findViewById(R.id.recycler_tabela_info)
        recyclerTabelaAvaliacao = root.findViewById(R.id.recycler_tabela_avaliacao)
        comentarioGeral = root.findViewById(R.id.comentario_geral)
        imagemRedacao = root.findViewById(R.id.imagem_redacao)
        recyclerComentarios = root.findViewById(R.id.recycler_comentarios)
        txtSemDados = root.findViewById(R.id.txtSemDados)
        txtPendente = root.findViewById(R.id.txtPendente)
        secaoDetalhes = root.findViewById(R.id.secao_detalhes)

        // Configurar LayoutManagers para as tabelas
        recyclerTabelaInfo.layoutManager = LinearLayoutManager(requireContext())
        recyclerTabelaAvaliacao.layoutManager = LinearLayoutManager(requireContext())

        btnTentarNovamente.setOnClickListener {
            buscarDetalhes()
        }

        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val callback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                requireActivity().supportFragmentManager.popBackStack()
            }
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, callback)

        if (url.isNotEmpty()) {
            buscarDetalhes()
        } else {
            exibirSemDados()
        }
    }

    private fun buscarDetalhes() {
        if (!isOnline()) {
            exibirTelaOffline()
            return
        }

        fetchDetalhes(url)
    }

    private fun fetchDetalhes(url: String) {
        CoroutineScope(Dispatchers.Main).launch {
            exibirCarregando()

            try {
                val doc = withContext(Dispatchers.IO) {
                    try {
                        val cookieHeader = CookieManager.getInstance().getCookie(url)
                        Jsoup.connect(url)
                            .header("Cookie", cookieHeader)
                            .userAgent("Mozilla/5.0 (Linux; Android 16; sdk_gphone64_x86_64 Build/BE2A.250530.026.D1; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/133.0.6943.137 Mobile Safari/537.36")
                            .timeout(15000)
                            .get()
                    } catch (e: Exception) {
                        Log.e("RedacaoDetalhes", "Erro na conexão", e)
                        null
                    }
                }

                progressBar.visibility = View.GONE

                if (doc != null) {
                    // Verificar se a página contém o elemento crítico
                    elementoCriticoPresente = doc.selectFirst(TITULO_SELECTOR) != null

                    if (elementoCriticoPresente) {
                        salvarCache(doc.outerHtml())
                        parseAndDisplay(doc)
                        exibirConteudoOnline()
                    } else {
                        exibirTelaOffline()
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

    private fun parseAndDisplay(doc: org.jsoup.nodes.Document) {
        // Tema
        val tituloElement = doc.selectFirst(TITULO_SELECTOR)
        tema.text = tituloElement?.text() ?: ""

        // Verificar se a correção está pendente
        val alertaPendente = doc.selectFirst(ALERTA_PENDENTE_SELECTOR)
        val correcaoPendente = alertaPendente?.text()?.contains("A correção está pendente.") == true

        if (correcaoPendente) {
            // Modo correção pendente: exibir apenas o tema e aviso
            secaoDetalhes.visibility = View.GONE
            txtPendente.visibility = View.VISIBLE
            return
        }

        // Modo normal: exibir todos os detalhes
        secaoDetalhes.visibility = View.VISIBLE
        txtPendente.visibility = View.GONE

        // Tabela de Informações
        val infoTable = doc.selectFirst(TABELA_INFO_SELECTOR)
        if (infoTable != null) {
            parseTable(infoTable, recyclerTabelaInfo)
        } else {
            recyclerTabelaInfo.adapter = null
        }

        // Tabela de Avaliação
        val avaliacaoTable = doc.selectFirst(TABELA_AVALIACAO_SELECTOR)
        if (avaliacaoTable != null) {
            parseTable(avaliacaoTable, recyclerTabelaAvaliacao)
        } else {
            recyclerTabelaAvaliacao.adapter = null
        }

        // Comentário Geral
        val comentarioElement = doc.selectFirst(COMENTARIO_GERAL_SELECTOR)
        comentarioGeral.text = comentarioElement?.text() ?: ""

        // Imagem da Redação
        val imagemElement = doc.selectFirst(IMAGEM_REDACAO_SELECTOR)
        val imagemUrl = imagemElement?.attr("src")
        if (!imagemUrl.isNullOrBlank()) {
            Glide.with(this@RedacaoDetalhesFragment)
                .load(imagemUrl)
                .into(imagemRedacao)
            imagemRedacao.visibility = View.VISIBLE
        } else {
            imagemRedacao.visibility = View.GONE
        }

        // Comentários Específicos (pontos a melhorar)
        val comentariosElements = doc.select(COMENTARIOS_ESPECIFICOS_SELECTOR)
        val comentarios = mutableListOf<Comentario>()
        for (element in comentariosElements) {
            val header = element.selectFirst(".card-header")
            val body = element.selectFirst(".card-body")
            if (header != null && body != null) {
                val titulo = header.text()
                val descricao = body.text()
                comentarios.add(Comentario(titulo, descricao))
            }
        }

        if (comentarios.isNotEmpty()) {
            recyclerComentarios.layoutManager = LinearLayoutManager(requireContext())
            recyclerComentarios.adapter = ComentariosAdapter(comentarios)
            recyclerComentarios.visibility = View.VISIBLE
        } else {
            recyclerComentarios.visibility = View.GONE
        }
    }

    private fun parseTable(table: Element, recyclerView: RecyclerView) {
        val items = mutableListOf<TableItem>()
        val rows = table.select("tr")

        for (row in rows) {
            val cells = row.select("td, th")
            if (cells.size >= 2) {
                items.add(
                    TableItem(
                        criterio = cells[0].text(),
                        nota = cells[1].text(),
                        isHeader = cells[0].tagName() == "th"
                    )
                )
            }
        }

        recyclerView.adapter = TableAdapter(items)
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
            val doc = Jsoup.parse(html)
            parseAndDisplay(doc)
            exibirConteudoOnline()
        } else {
            exibirSemDados()
        }
    }

    private fun exibirCarregando() {
        progressBar.visibility = View.VISIBLE
        contentLayout.visibility = View.GONE
        telaOffline.visibility = View.GONE
        txtSemDados.visibility = View.GONE
        txtPendente.visibility = View.GONE
    }

    private fun exibirConteudoOnline() {
        progressBar.visibility = View.GONE
        contentLayout.visibility = View.VISIBLE
        telaOffline.visibility = View.GONE
        txtSemDados.visibility = View.GONE
    }

    private fun exibirTelaOffline() {
        progressBar.visibility = View.GONE
        contentLayout.visibility = View.GONE
        telaOffline.visibility = View.VISIBLE
        txtSemDados.visibility = View.GONE
        txtPendente.visibility = View.GONE
    }

    private fun exibirSemDados() {
        progressBar.visibility = View.GONE
        contentLayout.visibility = View.GONE
        telaOffline.visibility = View.GONE
        txtSemDados.visibility = View.VISIBLE
        txtPendente.visibility = View.GONE
    }

    private fun isOnline(): Boolean {
        return try {
            val cm = requireContext().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork ?: return false
            val capabilities = cm.getNetworkCapabilities(network) ?: return false
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } catch (_: Exception) {
            false
        }
    }

    data class Comentario(val titulo: String, val descricao: String)
    data class TableItem(val criterio: String, val nota: String, val isHeader: Boolean)

    private class ComentariosAdapter(private val comentarios: List<Comentario>) :
        RecyclerView.Adapter<ComentariosAdapter.ViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_comentario, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val comentario = comentarios[position]
            holder.titulo.text = comentario.titulo
            holder.descricao.text = comentario.descricao
        }

        override fun getItemCount(): Int = comentarios.size

        class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val titulo: TextView = itemView.findViewById(R.id.titulo)
            val descricao: TextView = itemView.findViewById(R.id.descricao)
        }
    }

    private inner class TableAdapter(private val items: List<TableItem>) :
        RecyclerView.Adapter<TableAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvCriterio: TextView = view.findViewById(R.id.tv_criterio)
            val tvNota: TextView = view.findViewById(R.id.tv_nota)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_tabela, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            with(holder) {
                tvCriterio.text = item.criterio
                tvNota.text = item.nota

                if (item.isHeader) {
                    tvCriterio.setTextAppearance(com.google.android.material.R.style.TextAppearance_MaterialComponents_Subtitle1)
                    tvCriterio.setTypeface(tvCriterio.typeface, Typeface.BOLD)
                    tvNota.setTextAppearance(com.google.android.material.R.style.TextAppearance_MaterialComponents_Subtitle1)
                    tvNota.setTypeface(tvNota.typeface, Typeface.BOLD)
                } else {
                    tvCriterio.setTextAppearance(com.google.android.material.R.style.TextAppearance_MaterialComponents_Body1)
                    tvNota.setTextAppearance(com.google.android.material.R.style.TextAppearance_MaterialComponents_Body1)
                    tvCriterio.setTypeface(tvCriterio.typeface, Typeface.NORMAL)
                    tvNota.setTypeface(tvNota.typeface, Typeface.NORMAL)
                }
            }
        }

        override fun getItemCount(): Int = items.size
    }
}