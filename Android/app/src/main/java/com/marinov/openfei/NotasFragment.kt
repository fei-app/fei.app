package com.marinov.openfei

import android.content.res.Configuration
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import androidx.core.graphics.toColorInt
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class NotasFragment : Fragment(), MainActivity.RefreshableFragment {

    private lateinit var loadingContainer: FrameLayout
    private lateinit var contentContainer: LinearLayout
    private lateinit var barOffline: LinearLayout
    private lateinit var rvNotas: RecyclerView
    private var adView: AdView? = null
    private var isRefreshing = false
    private var isFirstLoad = true

    // Estrutura de dados que alimenta cada Cartão (Card)
    data class SubjectData(
        val codigo: String,
        val nome: String,
        val notas: List<Dados.Nota>,
        val media: String
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_notas, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        loadingContainer = view.findViewById(R.id.loadingContainer)
        contentContainer = view.findViewById(R.id.contentContainer)
        barOffline = view.findViewById(R.id.barOffline)
        rvNotas = view.findViewById(R.id.rvNotas)
        val btnLogin: Button = view.findViewById(R.id.btnLogin)

        // Configuração responsiva do LayoutManager: 2 colunas se for tablet (>= 600dp), 1 coluna se for celular
        val isTablet = resources.configuration.smallestScreenWidthDp >= 600
        rvNotas.layoutManager = if (isTablet) {
            GridLayoutManager(requireContext(), 2)
        } else {
            LinearLayoutManager(requireContext())
        }

        // Inicializa AdMob
        adView = view.findViewById(R.id.adView)
        val adRequest = AdRequest.Builder().build()
        adView?.loadAd(adRequest)

        btnLogin.setOnClickListener { loadNotas() }

        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    (activity as? MainActivity)?.navigateToHome()
                }
            }
        )

        isFirstLoad = savedInstanceState == null
        loadNotas()
    }

    override fun onResume() {
        super.onResume()
        adView?.resume()
    }

    override fun onPause() {
        adView?.pause()
        super.onPause()
    }

    override fun onDestroyView() {
        adView?.destroy()
        adView = null
        super.onDestroyView()
    }

    override fun onRefresh() {
        isRefreshing = true
        loadNotas()
    }

    private fun loadNotas() {
        lifecycleScope.launch {
            val mainActivity = activity as? MainActivity ?: return@launch
            val status = mainActivity.checkConnectionAndSession()

            when (status) {
                MainActivity.STATUS_OFFLINE, MainActivity.STATUS_LOGIN_NEEDED -> {
                    showOfflineBar()
                    loadNotasData(online = false)
                }
                MainActivity.STATUS_ONLINE_OK -> {
                    hideOfflineBar()
                    loadNotasData(online = true)
                }
            }

            if (isRefreshing) {
                mainActivity.setRefreshing(false)
                isRefreshing = false
            }
        }
    }

    private suspend fun loadNotasData(online: Boolean) {
        try {
            // Busca notas, médias e disciplinas em paralelo
            val notasDeferred = lifecycleScope.async(Dispatchers.IO) {
                Dados.obterNotas(online = online)
            }
            val disciplinasDeferred = lifecycleScope.async(Dispatchers.IO) {
                runCatching { Dados.obterDisciplinas(online = online) }.getOrElse { emptyList() }
            }
            val mediasDeferred = lifecycleScope.async(Dispatchers.IO) {
                runCatching { Dados.obterMedias(online = online) }.getOrElse { emptyMap() }
            }

            val notas = notasDeferred.await()
            val disciplinas = disciplinasDeferred.await()
            val medias = mediasDeferred.await()

            // Atualiza a UI com os dados agregados
            withContext(Dispatchers.Main) {
                if (notas.isNotEmpty()) {
                    buildCards(notas, disciplinas, medias)
                    showContent()
                } else {
                    showEmptyState()
                }
            }
        } catch (e: Exception) {
            Log.e("NotasFragment", "Erro ao obter notas", e)
            withContext(Dispatchers.Main) {
                showEmptyState()
            }
        }
    }

    private fun buildCards(notas: List<Dados.Nota>, disciplinas: List<Dados.Disciplina>, medias: Map<String, String>) {
        val disciplinasMap = disciplinas.associateBy { it.codigo }
        val notasAgrupadas = notas.groupBy { it.codigoDisciplina }

        // Mapeia para os objetos de cartão organizados alfabeticamente
        val cardsData = notasAgrupadas.map { (codigo, listaNotas) ->
            SubjectData(
                codigo = codigo,
                nome = disciplinasMap[codigo]?.nome ?: codigo,
                notas = listaNotas,
                media = medias[codigo] ?: ""
            )
        }.sortedBy { it.nome }

        rvNotas.adapter = NotasAdapter(cardsData)
    }

    private fun showContent() {
        if (isAdded) {
            loadingContainer.visibility = View.GONE
            contentContainer.visibility = View.VISIBLE
        }
    }

    private fun showEmptyState() {
        if (isAdded) {
            loadingContainer.visibility = View.GONE
            contentContainer.visibility = View.VISIBLE
            rvNotas.adapter = null
        }
    }

    private fun showOfflineBar() {
        if (isAdded) barOffline.visibility = View.VISIBLE
    }

    private fun hideOfflineBar() {
        if (isAdded) barOffline.visibility = View.GONE
    }

    // ======================== ADAPTER DOS CARTÕES ========================
    inner class NotasAdapter(private val items: List<SubjectData>) : RecyclerView.Adapter<NotasAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvDisciplinaTitle: TextView = view.findViewById(R.id.tvDisciplinaTitle)
            val llNotasContainer: LinearLayout = view.findViewById(R.id.llNotasContainer)
            val tvMedia: TextView = view.findViewById(R.id.tvMedia)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_nota_card, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            val context = holder.itemView.context

            // Título principal do Card
            holder.tvDisciplinaTitle.text = "${item.codigo} - ${item.nome}"

            // Popula dinamicamente a lista de provas (P1, P2, PJ...)
            holder.llNotasContainer.removeAllViews()
            for (nota in item.notas) {
                val valorExibicao = nota.valor.takeIf { it.isNotBlank() } ?: "--"
                val tvNota = TextView(context).apply {
                    text = "${nota.tipoProva}: $valorExibicao"
                    setTextColor(ContextCompat.getColor(context, R.color.colorOnSurface))
                    textSize = 14f
                    setPadding(0, 4, 0, 4)
                }
                holder.llNotasContainer.addView(tvNota)
            }

            // Lógica de exibição da Média (com validação de cor)
            if (item.media.isNotBlank()) {
                holder.tvMedia.visibility = View.VISIBLE

                // Adapta cores para Light / Dark mode com alto contraste
                val isNightMode = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
                val colorAprovado = if (isNightMode) "#81C784".toColorInt() else "#2E7D32".toColorInt()
                val colorReprovado = if (isNightMode) "#E57373".toColorInt() else "#C62828".toColorInt()
                val colorDefault = ContextCompat.getColor(context, R.color.colorOnSurface)

                val mediaValue = item.media.replace(",", ".").toFloatOrNull()

                if (mediaValue != null) {
                    if (mediaValue >= 5.0f) {
                        holder.tvMedia.text = "Média: ${item.media} (APROVADO)"
                        holder.tvMedia.setTextColor(colorAprovado)
                    } else {
                        holder.tvMedia.text = "Média: ${item.media} (REPROVADO)"
                        holder.tvMedia.setTextColor(colorReprovado)
                    }
                } else {
                    holder.tvMedia.text = "Média: ${item.media}"
                    holder.tvMedia.setTextColor(colorDefault)
                }
            } else {
                // Se a média não existe, apenas oculta
                holder.tvMedia.visibility = View.GONE
            }
        }

        override fun getItemCount(): Int = items.size
    }
}