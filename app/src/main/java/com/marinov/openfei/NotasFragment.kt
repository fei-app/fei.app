package com.marinov.openfei

import android.graphics.Typeface
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class NotasFragment : Fragment(), MainActivity.RefreshableFragment {

    private lateinit var loadingContainer: FrameLayout
    private lateinit var contentContainer: LinearLayout
    private lateinit var tableNotas: TableLayout
    private lateinit var barOffline: LinearLayout
    private lateinit var legendCard: MaterialCardView
    private lateinit var legendContainer: LinearLayout
    private lateinit var legendTitle: TextView
    private var isRefreshing = false
    private var isFirstLoad = true

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
        tableNotas = view.findViewById(R.id.tableNotas)
        barOffline = view.findViewById(R.id.barOffline)
        legendCard = view.findViewById(R.id.legendCard)
        legendContainer = view.findViewById(R.id.legendContainer)
        legendTitle = view.findViewById(R.id.legendTitle)
        val btnLogin: Button = view.findViewById(R.id.btnLogin)
        btnLogin.setOnClickListener { navigateToHome() }

        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    navigateToHome()
                }
            }
        )

        // Se há estado salvo, não é a primeira carga completa (ex: rotação)
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
                    // Offline ou deslogado: tenta carregar do cache via Dados
                    showOfflineBar()
                    loadNotasData(online = false)
                }
                MainActivity.STATUS_ONLINE_OK -> {
                    // Online: busca do servidor
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
            val notas = Dados.obterNotas(online = online)
            withContext(Dispatchers.Main) {
                if (notas.isNotEmpty()) {
                    buildTable(notas)
                    showContent()
                } else {
                    showEmptyState()
                }
                // Carrega a legenda independentemente das notas (mas apenas se online ou cache tiver)
                loadLegend()
            }
        } catch (e: Exception) {
            Log.e("NotasFragment", "Erro ao obter notas", e)
            withContext(Dispatchers.Main) {
                showEmptyState()
                hideLegend()
            }
        }
    }

    private fun buildTable(notas: List<Dados.Nota>) {
        val context = context ?: return
        tableNotas.removeAllViews()

        if (notas.isEmpty()) return

        val disciplinasMap = linkedMapOf<String, MutableMap<String, String>>()
        val tiposProvaSet = mutableSetOf<String>()

        for (nota in notas) {
            val map = disciplinasMap.getOrPut(nota.codigoDisciplina) { mutableMapOf() }
            map[nota.tipoProva] = nota.valor
            tiposProvaSet.add(nota.tipoProva)
        }

        val tiposFixos = setOf("P1", "P2", "P3", "PJ")

        val tiposVisiveis = tiposProvaSet.filter { tipo ->
            tipo in tiposFixos || disciplinasMap.values.any { notasMap ->
                notasMap[tipo]?.let { it.isNotBlank() && it != "-" } ?: false
            }
        }.sorted()

        val headerRow = TableRow(context).apply {
            setBackgroundColor(ContextCompat.getColor(context, R.color.header_bg))
        }
        val disciplinaHeader = createCell("Código", isHeader = true)
        disciplinaHeader.setTextColor(ContextCompat.getColor(context, R.color.colorOnSurface))
        headerRow.addView(disciplinaHeader)

        for (tipo in tiposVisiveis) {
            val cell = createCell(tipo, isHeader = true)
            cell.setTextColor(ContextCompat.getColor(context, R.color.colorOnSurface))
            headerRow.addView(cell)
        }
        tableNotas.addView(headerRow)

        val colorDefault = ContextCompat.getColor(context, R.color.colorOnSurface)
        for ((codigo, notasMap) in disciplinasMap) {
            val row = TableRow(context)
            row.setBackgroundColor(ContextCompat.getColor(context, android.R.color.transparent))

            val codigoCell = createCell(codigo, isHeader = false)
            codigoCell.setTextColor(colorDefault)
            row.addView(codigoCell)

            for (tipo in tiposVisiveis) {
                val valor = notasMap[tipo] ?: "-"
                val cell = createCell(valor, isHeader = false)
                cell.setTextColor(colorDefault)
                row.addView(cell)
            }
            tableNotas.addView(row)
        }
    }

    private suspend fun loadLegend() {
        try {
            val disciplinas = Dados.obterDisciplinas(online = true)
            withContext(Dispatchers.Main) {
                legendContainer.removeAllViews()
                if (disciplinas.isEmpty()) {
                    hideLegend()
                    return@withContext
                }
                legendCard.visibility = View.VISIBLE
                legendTitle.visibility = View.VISIBLE
                val context = requireContext()
                for (d in disciplinas) {
                    val item = TextView(context).apply {
                        text = "${d.codigo} - ${d.nome}"
                        setTextColor(ContextCompat.getColor(context, R.color.colorOnSurface))
                        textSize = 14f
                        setPadding(0, 4, 0, 4)
                    }
                    legendContainer.addView(item)
                }
            }
        } catch (e: Exception) {
            Log.e("NotasFragment", "Erro ao carregar legenda", e)
            withContext(Dispatchers.Main) {
                hideLegend()
            }
        }
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
            tableNotas.removeAllViews()
            hideLegend()
        }
    }

    private fun hideLegend() {
        if (isAdded) {
            legendCard.visibility = View.GONE
            legendTitle.visibility = View.GONE
            legendContainer.removeAllViews()
        }
    }

    private fun createCell(text: String, isHeader: Boolean): TextView {
        val context = requireContext()
        return TextView(context).apply {
            this.text = text
            setTypeface(null, if (isHeader) Typeface.BOLD else Typeface.NORMAL)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (isHeader) 13f else 12f)
            val paddingH = (8 * resources.displayMetrics.density).toInt()
            val paddingV = (6 * resources.displayMetrics.density).toInt()
            setPadding(paddingH, paddingV, paddingH, paddingV)
            textAlignment = View.TEXT_ALIGNMENT_CENTER
        }
    }

    private fun showOfflineBar() {
        if (isAdded) barOffline.visibility = View.VISIBLE
    }

    private fun hideOfflineBar() {
        if (isAdded) barOffline.visibility = View.GONE
    }

    private fun navigateToHome() {
        (activity as? MainActivity)?.navigateToHome()
    }
}