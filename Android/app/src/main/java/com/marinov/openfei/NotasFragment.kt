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
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdView
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
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
    private var adView: AdView? = null
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
            // Busca notas e disciplinas em paralelo
            val notasDeferred = lifecycleScope.async(Dispatchers.IO) {
                Dados.obterNotas(online = online)
            }
            val disciplinasDeferred = lifecycleScope.async(Dispatchers.IO) {
                runCatching { Dados.obterDisciplinas(online = online) }.getOrElse { emptyList() }
            }

            val notas = notasDeferred.await()
            val disciplinas = disciplinasDeferred.await()

            // Só toca na UI depois que ambos os dados chegaram
            withContext(Dispatchers.Main) {
                if (notas.isNotEmpty()) {
                    buildTable(notas)
                } else {
                    tableNotas.removeAllViews()
                }

                buildLegend(disciplinas)

                // Revela o conteúdo de uma vez, sem piscar
                if (notas.isNotEmpty()) {
                    showContent()
                } else {
                    showEmptyState()
                }
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

        // Monta as colunas: tipos fixos + tipos adicionais que tenham ao menos um valor válido
        val tiposVisiveis = (
                tiposFixos +
                        tiposProvaSet.filter { tipo ->
                            tipo !in tiposFixos &&
                                    disciplinasMap.values.any { notasMap ->
                                        notasMap[tipo]?.let {
                                            it.isNotBlank() && it != "-" && it != "--"
                                        } ?: false
                                    }
                        }
                ).sorted()

        // Linha de cabeçalho
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

        // Linhas de dados
        for ((codigo, notasMap) in disciplinasMap) {
            val row = TableRow(context)
            row.setBackgroundColor(ContextCompat.getColor(context, android.R.color.transparent))

            val codigoCell = createCell(codigo, isHeader = false)
            codigoCell.setTextColor(colorDefault)
            row.addView(codigoCell)

            for (tipo in tiposVisiveis) {
                // 🔧 CORREÇÃO: qualquer valor nulo, vazio ou em branco vira "--"
                val valor = notasMap[tipo]?.takeIf { it.isNotBlank() } ?: "--"
                val cell = createCell(valor, isHeader = false)
                cell.setTextColor(colorDefault)
                row.addView(cell)
            }
            tableNotas.addView(row)
        }
    }

    /** Monta a legenda na UI — deve ser chamado sempre na Main thread, antes de showContent(). */
    private fun buildLegend(disciplinas: List<Dados.Disciplina>) {
        legendContainer.removeAllViews()
        if (disciplinas.isEmpty()) {
            hideLegend()
            return
        }
        val context = context ?: return
        legendCard.visibility = View.VISIBLE
        legendTitle.visibility = View.VISIBLE
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
}