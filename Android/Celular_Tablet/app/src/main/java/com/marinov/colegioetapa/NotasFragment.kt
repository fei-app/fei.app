package com.marinov.colegioetapa

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Typeface
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.text.DecimalFormat
import androidx.core.content.edit

class NotasFragment : Fragment(), MainActivity.RefreshableFragment { // Implemente a interface

    private companion object {
        const val URL_NOTAS = "https://areaexclusiva.colegioetapa.com.br/provas/notas"
        const val PREFS = "notas_prefs"
        const val KEY_HTML = "cache_html"
    }

    private lateinit var tableNotas: TableLayout
    private lateinit var barOffline: LinearLayout
    private lateinit var cache: CacheHelper
    private var isRefreshing = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_notas, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        tableNotas = view.findViewById(R.id.tableNotas)
        barOffline = view.findViewById(R.id.barOffline)
        val btnLogin: Button = view.findViewById(R.id.btnLogin)
        btnLogin.setOnClickListener { navigateToHome() }

        cache = CacheHelper(requireContext())
        fetchNotas()

        val callback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                (activity as? MainActivity)?.navigateToHome()
            }
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, callback)
    }

    // Implementação do Pull-to-Refresh
    override fun onRefresh() {
        Log.d("NotasFragment", "Pull-to-Refresh acionado")
        isRefreshing = true
        fetchNotas()
    }

    @SuppressLint("SetTextI18n")
    private fun parseAndBuildTable(table: Element) {
        // Verificação crítica de contexto
        val context = context ?: return

        tableNotas.removeAllViews()
        val colorDefault = ContextCompat.getColor(context, R.color.colorOnSurface)
        val colorHeaderBg = ContextCompat.getColor(context, R.color.header_bg)
        val colorHeaderText = ContextCompat.getColor(context, R.color.colorOnSurface)

        val headers = table.select("thead th")
        if (headers.isNotEmpty()) {
            val headerRow = TableRow(context)
            headerRow.setBackgroundColor(colorHeaderBg)

            val thCode = createCell(headers[1].text(), true, context)
            thCode.setTextColor(colorHeaderText)
            headerRow.addView(thCode)

            for (i in 2 until headers.size) {
                val th = createCell(headers[i].text(), true, context)
                th.setTextColor(colorHeaderText)
                headerRow.addView(th)
            }
            tableNotas.addView(headerRow)
        }

        val rows = table.select("tbody > tr")
        val notaCols = if (headers.size > 2) headers.size - 2 else 0
        val sums = DoubleArray(notaCols)
        val counts = IntArray(notaCols)

        for (r in rows) {
            val row = TableRow(context)
            row.setBackgroundColor(ContextCompat.getColor(context, android.R.color.transparent))
            val cols = r.children()

            val code = if (cols.size > 1) cols[1].text() else ""
            val tvCode = createCell(code, false, context)
            tvCode.setTextColor(colorDefault)
            row.addView(tvCode)

            for (j in 2 until cols.size) {
                val colIndex = j - 2
                val cell = cols[j]

                // *** LÓGICA ATUALIZADA PARA EXTRAIR A NOTA ***
                // 1. Encontra o div que contém a palavra "Nota".
                val notaContainer = cell.select("div.d-flex.flex-column").firstOrNull {
                    it.selectFirst("span.font-weight-bold")?.text()?.equals("Nota", ignoreCase = true) == true
                }

                // 2. Extrai o texto da nota. Se o contêiner for encontrado, limpa a palavra "Nota".
                //    Caso contrário, usa o texto da célula inteira como fallback.
                val valText =
                    notaContainer?.text()?.replace("Nota", "", ignoreCase = true)?.trim() ?: cell.text().trim()
                // *** FIM DA LÓGICA ATUALIZADA ***

                if (valText != "--") {
                    try {
                        // Substitui vírgula por ponto para conversão em Double e remove "*" de faltas
                        val value = valText.replace(',', '.').replace("*", "").toDouble()
                        sums[colIndex] += value
                        counts[colIndex]++
                    } catch (_: NumberFormatException) {
                        // Ignorar valores não numéricos (como "*F")
                    }
                }

                val tv = createCell(valText, false, context)
                tv.setTextColor(colorDefault)

                // *** BLOCO WHEN ATUALIZADO ***
                when {
                    cols[j].hasClass("bg-success") -> {
                        tv.setBackgroundResource(R.drawable.bg_success_rounded)
                        tv.setTextColor(ContextCompat.getColor(context, android.R.color.white))
                    }
                    cols[j].hasClass("bg-warning") -> {
                        tv.setBackgroundResource(R.drawable.bg_warning_rounded)
                        tv.setTextColor(ContextCompat.getColor(context, android.R.color.black))
                    }
                    cols[j].hasClass("bg-danger") -> {
                        tv.setBackgroundResource(R.drawable.bg_danger_rounded)
                        tv.setTextColor(ContextCompat.getColor(context, android.R.color.white))
                    }
                    // Cores de Falta Adicionadas
                    cols[j].hasClass("bg-info") -> {
                        // Falta justificada (Azul claro)
                        // Assumindo que você criará R.drawable.bg_info_rounded
                        tv.setBackgroundResource(R.drawable.bg_info_rounded)
                        tv.setTextColor(ContextCompat.getColor(context, android.R.color.white))
                    }
                    cols[j].hasClass("amber") -> {
                        // Falta não justificada (Laranja escuro)
                        // Assumindo que você criará R.drawable.bg_amber_rounded
                        tv.setBackgroundResource(R.drawable.bg_amber_rounded)
                        tv.setTextColor(ContextCompat.getColor(context, android.R.color.white))
                    }
                }
                // *** FIM DO BLOCO WHEN ***
                row.addView(tv)
            }
            tableNotas.addView(row)
        }

        if (headers.isNotEmpty()) {
            val avgRow = TableRow(context)
            avgRow.setBackgroundColor(colorHeaderBg)

            val firstCell = createCell("Média", true, context)
            firstCell.setTextColor(colorHeaderText)
            avgRow.addView(firstCell)

            val decimalFormat = DecimalFormat("#.##")
            for (k in 0 until notaCols) {
                val avgValue = if (counts[k] > 0) decimalFormat.format(sums[k] / counts[k]) else "--"
                val avgCell = createCell(avgValue, true, context)
                avgCell.setTextColor(colorHeaderText)
                avgRow.addView(avgCell)
            }
            tableNotas.addView(avgRow)
        }
    }

    private fun createCell(txt: String, isHeader: Boolean, context: Context): TextView {
        return TextView(context).apply {
            text = txt
            setTypeface(null, if (isHeader) Typeface.BOLD else Typeface.NORMAL)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (isHeader) 13f else 12f)
            val h = (8 * resources.displayMetrics.density).toInt()
            val v = (6 * resources.displayMetrics.density).toInt()
            setPadding(h, v, h, v)
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

    private fun fetchNotas() {
        CoroutineScope(Dispatchers.Main).launch {
            try {
                val doc = withContext(Dispatchers.IO) {
                    try {
                        val cookieHeader = CookieManager.getInstance().getCookie(URL_NOTAS)
                        Jsoup.connect(URL_NOTAS)
                            .header("Cookie", cookieHeader)
                            .userAgent("Mozilla/5.0 (Linux; Android 16; sdk_gphone64_x86_64 Build/BE2A.250530.026.D1; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/133.0.6943.137 Mobile Safari/537.36")
                            .timeout(15000)
                            .get()
                    } catch (e: Exception) {
                        Log.e("NotasFragment", "Erro ao conectar", e)
                        null
                    }
                }

                if (!isAdded) return@launch

                if (doc != null) {
                    val newTable = doc.selectFirst(
                        "#page-content-wrapper > div.d-lg-flex > div.container-fluid.p-3 > " +
                                "div.card.bg-transparent.border-0 > div.card-body.px-0.px-md-3 > " +
                                "div:nth-child(2) > div.card-body > table"
                    )
                    if (newTable != null) {
                        cache.saveHtml(newTable.outerHtml())
                        parseAndBuildTable(newTable)
                        hideOfflineBar()
                    } else {
                        showOfflineBar()
                        Log.e("NotasFragment", "Tabela não encontrada no HTML")
                        loadCachedData()
                    }
                } else {
                    loadCachedData()
                    showOfflineBar()
                    Log.e("NotasFragment", "Falha na conexão — usando cache")
                }
            } catch (e: Exception) {
                Log.e("NotasFragment", "Erro inesperado", e)
                if (isAdded) {
                    loadCachedData()
                    showOfflineBar()
                }
            } finally {
                // Parar o refresh indicador ao finalizar
                if (isRefreshing) {
                    (activity as? MainActivity)?.setRefreshing(false)
                    isRefreshing = false
                }
            }
        }
    }

    private fun loadCachedData() {
        if (!isAdded) return
        val html = cache.loadHtml()
        if (html != null) {
            val fake = Jsoup.parse(html)
            val table = fake.selectFirst("table")
            if (table != null) {
                parseAndBuildTable(table)
            }
        }

        // Se estava atualizando, parar o refresh
        if (isRefreshing) {
            (activity as? MainActivity)?.setRefreshing(false)
            isRefreshing = false
        }
    }

    private class CacheHelper(context: Context) {
        private val prefs: SharedPreferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        fun saveHtml(html: String) {
            prefs.edit { putString(KEY_HTML, html) }
        }

        fun loadHtml(): String? {
            return prefs.getString(KEY_HTML, null)
        }
    }
}