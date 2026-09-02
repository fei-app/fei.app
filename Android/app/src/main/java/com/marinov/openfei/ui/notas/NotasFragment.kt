package com.marinov.openfei.ui.notas

import android.annotation.SuppressLint
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
import com.marinov.openfei.data.Disciplina
import com.marinov.openfei.data.DisciplinasRepository
import com.marinov.openfei.data.Nota
import com.marinov.openfei.data.NotasRepository
import com.marinov.openfei.ui.main.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class NotasFragment : Fragment(), MainActivity.RefreshableFragment {
    private lateinit var loadingContainer: FrameLayout
    private lateinit var contentContainer: LinearLayout
    private lateinit var barOffline: LinearLayout
    private lateinit var rvNotas: RecyclerView
    private lateinit var tvEmptyNotas: TextView
    private var isRefreshing = false
    private var isFirstLoad = true

    data class SubjectData(
        val codigo: String,
        val nome: String,
        val notas: List<Nota>,
        val media: String
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(com.marinov.openfei.R.layout.fragment_notas, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        loadingContainer = view.findViewById(com.marinov.openfei.R.id.loadingContainer)
        contentContainer = view.findViewById(com.marinov.openfei.R.id.contentContainer)
        barOffline = view.findViewById(com.marinov.openfei.R.id.barOffline)
        rvNotas = view.findViewById(com.marinov.openfei.R.id.rvNotas)
        tvEmptyNotas = view.findViewById(com.marinov.openfei.R.id.tvEmptyNotas)

        val btnLogin: Button = view.findViewById(com.marinov.openfei.R.id.btnLogin)
        val isTablet = resources.configuration.smallestScreenWidthDp >= 600
        rvNotas.layoutManager = if (isTablet) {
            GridLayoutManager(requireContext(), 2)
        } else {
            LinearLayoutManager(requireContext())
        }

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
            val notasDeferred = lifecycleScope.async(Dispatchers.IO) {
                NotasRepository.obterNotas(online = online)
            }
            val disciplinasDeferred = lifecycleScope.async(Dispatchers.IO) {
                runCatching { DisciplinasRepository.obterDisciplinas(online = online) }.getOrElse { emptyList() }
            }
            val mediasDeferred = lifecycleScope.async(Dispatchers.IO) {
                runCatching { NotasRepository.obterMedias(online = online) }.getOrElse { emptyMap() }
            }

            val notas = notasDeferred.await()
            val disciplinas = disciplinasDeferred.await()
            val medias = mediasDeferred.await()

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

    private fun buildCards(notas: List<Nota>, disciplinas: List<Disciplina>, medias: Map<String, String>) {
        val disciplinasMap = disciplinas.associateBy { it.codigo }
        val notasAgrupadas = notas.groupBy { it.codigoDisciplina }
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
            tvEmptyNotas.visibility = View.GONE
            rvNotas.visibility = View.VISIBLE
        }
    }

    private fun showEmptyState() {
        if (isAdded) {
            loadingContainer.visibility = View.GONE
            contentContainer.visibility = View.VISIBLE
            rvNotas.adapter = null
            rvNotas.visibility = View.GONE
            tvEmptyNotas.visibility = View.VISIBLE
        }
    }

    private fun showOfflineBar() {
        if (isAdded) barOffline.visibility = View.VISIBLE
    }

    private fun hideOfflineBar() {
        if (isAdded) barOffline.visibility = View.GONE
    }

    inner class NotasAdapter(private val items: List<SubjectData>) : RecyclerView.Adapter<NotasAdapter.ViewHolder>() {
        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvDisciplinaTitle: TextView = view.findViewById(com.marinov.openfei.R.id.tvDisciplinaTitle)
            val llNotasContainer: LinearLayout = view.findViewById(com.marinov.openfei.R.id.llNotasContainer)
            val tvMedia: TextView = view.findViewById(com.marinov.openfei.R.id.tvMedia)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(com.marinov.openfei.R.layout.item_nota_card, parent, false)
            return ViewHolder(view)
        }

        @SuppressLint("SetTextI18n")
        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            val context = holder.itemView.context

            holder.tvDisciplinaTitle.text = "${item.codigo} - ${item.nome}"
            holder.llNotasContainer.removeAllViews()

            for (nota in item.notas) {
                val valorExibicao = nota.valor.takeIf { it.isNotBlank() } ?: context.getString(com.marinov.openfei.R.string.sem_valor)
                val tvNota = TextView(context).apply {
                    text = "${nota.tipoProva}: $valorExibicao"
                    setTextColor(ContextCompat.getColor(context, com.marinov.openfei.R.color.colorOnSurface))
                    textSize = 14f
                    setPadding(0, 4, 0, 4)
                }
                holder.llNotasContainer.addView(tvNota)
            }

            if (item.media.isNotBlank()) {
                holder.tvMedia.visibility = View.VISIBLE

                val isNightMode = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
                val colorAprovado = if (isNightMode) "#81C784".toColorInt() else "#2E7D32".toColorInt()
                val colorReprovado = if (isNightMode) "#E57373".toColorInt() else "#C62828".toColorInt()
                val colorDefault = ContextCompat.getColor(context, com.marinov.openfei.R.color.colorOnSurface)

                val mediaValue = item.media.replace(",", ".").toFloatOrNull()

                if (mediaValue != null) {
                    if (mediaValue >= 5.0f) {
                        holder.tvMedia.text = context.getString(com.marinov.openfei.R.string.notas_media_aprovado, item.media)
                        holder.tvMedia.setTextColor(colorAprovado)
                    } else {
                        holder.tvMedia.text = context.getString(com.marinov.openfei.R.string.notas_media_reprovado, item.media)
                        holder.tvMedia.setTextColor(colorReprovado)
                    }
                } else {
                    holder.tvMedia.text = context.getString(com.marinov.openfei.R.string.notas_media, item.media)
                    holder.tvMedia.setTextColor(colorDefault)
                }
            } else {
                holder.tvMedia.visibility = View.GONE
            }
        }

        override fun getItemCount(): Int = items.size
    }
}