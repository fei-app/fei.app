package com.marinov.colegioetapa

import android.content.Context
import android.content.res.Configuration
import android.graphics.Typeface
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.viewpager2.widget.ViewPager2
import com.bumptech.glide.Glide
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.marinov.colegioetapa.WebViewFragment.Companion.createArgs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.IOException
import java.util.Calendar
import java.util.Objects

class HomeFragment : Fragment() {

    private var swipeRefreshLayout: SwipeRefreshLayout? = null
    private var viewPager: ViewPager2? = null
    private var newsRecyclerView: RecyclerView? = null
    private var layoutSemInternet: LinearLayout? = null
    private var btnTentarNovamente: MaterialButton? = null
    private var loadingContainer: View? = null
    private var contentContainer: View? = null
    private var txtStuckHint: TextView? = null
    private var carouselLoadingIndicator: CircularProgressIndicator? = null
    private var newsSectionContainer: View? = null
    private var recentGradesSectionContainer: View? = null
    private var tableRecentGrades: TableLayout? = null
    private var topLoadingBar: View? = null

    // --- Safe Mode Layout ---
    private var layoutSafeMode: LinearLayout? = null
    private var btnOpenProvas: MaterialButton? = null

    // --- Adapters ---
    private lateinit var carouselAdapter: CarouselAdapter
    private lateinit var newsAdapter: NewsAdapter

    // --- State ---
    private var shouldBlockNavigation = false
    private var isFragmentDestroyed = false

    // --- Data Lists ---
    private val carouselItems: MutableList<CarouselItem> = mutableListOf()
    private val newsItems: MutableList<NewsItem> = mutableListOf()
    private var recentGradesCache: List<NotaRecente> = emptyList()
    private var calendarExamsCache: List<ProvaCalendario> = emptyList()

    private val handler = Handler(Looper.getMainLooper())

    data class NotaRecente(val codigo: String, val conjunto: String, val nota: String, val data: Calendar) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as NotaRecente
            return codigo == other.codigo && conjunto == other.conjunto && nota == other.nota
        }
        override fun hashCode(): Int = Objects.hash(codigo, conjunto, nota)
    }
    data class ProvaCalendario(val data: Calendar, val codigo: String, val conjunto: Int)
    data class Nota(val codigo: String, val conjunto: Int, val valor: String)

    private companion object {
        const val PREFS_NAME = "HomeFragmentCache"
        const val KEY_CAROUSEL_ITEMS = "carousel_items"
        const val KEY_NEWS_ITEMS = "news_items"
        const val KEY_RECENT_GRADES = "recent_grades"
        const val KEY_CACHE_TIMESTAMP = "cache_timestamp"
        const val KEY_CALENDAR_EXAMS = "calendar_exams"
        const val KEY_CALENDAR_TIMESTAMP = "calendar_timestamp"
        const val SEVEN_DAYS_IN_MILLIS = 7 * 24 * 60 * 60 * 1000L

        const val HOME_URL = "https://areaexclusiva.colegioetapa.com.br/home"
        const val NOTAS_URL = "https://areaexclusiva.colegioetapa.com.br/provas/notas"
        const val CALENDARIO_URL_BASE = "https://areaexclusiva.colegioetapa.com.br/provas/datas"
        const val OUT_URL = "https://areaexclusiva.colegioetapa.com.br"
        const val MAX_RECENT_GRADES = 8
        const val MESES = 12
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android 16; sdk_gphone64_x86_64 Build/BE2A.250530.026.D1; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/133.0.6943.137 Mobile Safari/537.36"
        const val PREFS_APP = "app_prefs"
        const val KEY_SAFE_MODE = "safe_mode"
    }

    private fun isSafeMode(): Boolean =
        context?.getSharedPreferences(PREFS_APP, Context.MODE_PRIVATE)
            ?.getBoolean(KEY_SAFE_MODE, true) ?: true

    override fun onAttach(context: Context) {
        super.onAttach(context)
        if (context !is WebViewFragment.LoginSuccessListener) {
            throw RuntimeException("$context deve implementar LoginSuccessListener")
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_home, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        isFragmentDestroyed = false
        shouldBlockNavigation = false
        initializeViews(view)
        setupAdapters()
        setupRecyclerView()
        setupListeners()
        configureCarouselHeight()
        loadInitialData()
    }

    override fun onPause() {
        super.onPause()
        shouldBlockNavigation = true
    }

    override fun onResume() {
        super.onResume()
        shouldBlockNavigation = false
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        configureCarouselHeight()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        isFragmentDestroyed = true
        handler.removeCallbacksAndMessages(null)
    }

    private fun initializeViews(view: View) {
        swipeRefreshLayout = view.findViewById(R.id.swipe_refresh_layout)
        loadingContainer = view.findViewById(R.id.loadingContainer)
        contentContainer = view.findViewById(R.id.contentContainer)
        layoutSemInternet = view.findViewById(R.id.layout_sem_internet)
        btnTentarNovamente = view.findViewById(R.id.btn_tentar_novamente)
        viewPager = view.findViewById(R.id.viewPager)
        newsRecyclerView = view.findViewById(R.id.newsRecyclerView)
        txtStuckHint = view.findViewById(R.id.txtStuckHint)
        carouselLoadingIndicator = view.findViewById(R.id.carouselLoadingIndicator)
        newsSectionContainer = view.findViewById(R.id.newsSectionContainer)
        recentGradesSectionContainer = view.findViewById(R.id.recentGradesSectionContainer)
        tableRecentGrades = view.findViewById(R.id.tableRecentGrades)
        topLoadingBar = view.findViewById(R.id.top_loading_bar)
        layoutSafeMode = view.findViewById(R.id.layoutSafeMode)
        btnOpenProvas = view.findViewById(R.id.btn_open_provas)
    }

    private fun setupAdapters() {
        carouselAdapter = CarouselAdapter()
        newsAdapter = NewsAdapter()
        viewPager?.adapter = carouselAdapter
        newsRecyclerView?.adapter = newsAdapter
    }

    private fun setupRecyclerView() {
        newsRecyclerView?.layoutManager =
            LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
    }

    private fun setupListeners() {
        btnTentarNovamente?.setOnClickListener { loadInitialData() }

        swipeRefreshLayout?.setOnRefreshListener {
            // Só executa o refresh se o layout estiver em estado normal (conteúdo visível e não em safe mode)
            if (isAdded && !isFragmentDestroyed && contentContainer?.visibility == View.VISIBLE &&
                layoutSafeMode?.visibility != View.VISIBLE && layoutSemInternet?.visibility != View.VISIBLE
            ) {
                fetchDataFromServer()
            } else {
                // Se não for permitido, para o refresh imediatamente
                swipeRefreshLayout?.isRefreshing = false
            }
        }

        btnOpenProvas?.setOnClickListener {
            (activity as? MainActivity)?.openCustomFragment(ProvasFragment())
        }
    }

    // --- Controle do SwipeRefreshLayout local baseado no estado da UI ---
    private fun updateSwipeRefreshState() {
        if (isFragmentDestroyed || swipeRefreshLayout == null) return
        val isNormalContent = contentContainer?.visibility == View.VISIBLE &&
                layoutSemInternet?.visibility != View.VISIBLE &&
                layoutSafeMode?.visibility != View.VISIBLE &&
                !isSafeMode()
        swipeRefreshLayout?.isEnabled = isNormalContent
        if (!isNormalContent) {
            swipeRefreshLayout?.isRefreshing = false
        }
    }

    // --- Data Fetching ---

    private fun loadInitialData() {
        if (isSafeMode()) {
            showSafeModeState()
            return
        }

        val hasContentCache = loadCache()
        updateUiWithCurrentData()

        if (hasInternetConnection()) {
            val hasAnyCache = hasContentCache || recentGradesCache.isNotEmpty()
            if (hasAnyCache) showContentState() else showLoadingState()
            fetchDataFromServer()
        } else {
            showOfflineState()
        }
    }

    private fun fetchDataFromServer() {
        if (contentContainer?.visibility == View.VISIBLE) {
            topLoadingBar?.visibility = View.VISIBLE
        }

        lifecycleScope.launch {
            try {
                val homeDoc = async(Dispatchers.IO) { fetchPageData(HOME_URL) }.await()
                if (isFragmentDestroyed) return@launch

                if (isValidSession(homeDoc)) {
                    processPageContent(homeDoc)
                    saveCache()

                    val prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    val calendarTimestamp = prefs.getLong(KEY_CALENDAR_TIMESTAMP, 0)
                    val isCalendarCacheValid =
                        System.currentTimeMillis() - calendarTimestamp < SEVEN_DAYS_IN_MILLIS

                    val allExams: List<ProvaCalendario>
                    if (isCalendarCacheValid && calendarExamsCache.isNotEmpty()) {
                        Log.d("HomeFragment", "Usando calendário do cache.")
                        allExams = calendarExamsCache
                    } else {
                        Log.d("HomeFragment", "Cache do calendário inválido ou vazio. Buscando da web.")
                        val calendarDocsDeferred = (1..MESES).map { mes ->
                            async(Dispatchers.IO) { fetchPageData("$CALENDARIO_URL_BASE?mes%5B%5D=$mes") }
                        }
                        val calendarDocs = try {
                            awaitAll(*calendarDocsDeferred.toTypedArray())
                        } catch (_: Exception) { emptyList() }
                        allExams = parseAllCalendarData(calendarDocs)
                        calendarExamsCache = allExams
                        saveCalendarCache(allExams)
                    }

                    val gradesDoc = withContext(Dispatchers.IO) { fetchPageData(NOTAS_URL) }
                    val allGrades = parseAllGradesData(gradesDoc)
                    val newRecentGrades = findRecentGrades(allExams, allGrades)

                    if (newRecentGrades != recentGradesCache) {
                        recentGradesCache = newRecentGrades
                        saveRecentGradesCache(newRecentGrades)
                        withContext(Dispatchers.Main) { setupRecentGradesTable(newRecentGrades) }
                    }

                    withContext(Dispatchers.Main) {
                        if (isFragmentDestroyed) return@withContext
                        showContentState()
                        updateUiWithCurrentData()
                    }
                } else {
                    handleInvalidSession()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    if (!isFragmentDestroyed) handleDataFetchError(e)
                }
            } finally {
                withContext(Dispatchers.Main) {
                    topLoadingBar?.visibility = View.GONE
                    // Para o indicador de refresh, independentemente do resultado
                    swipeRefreshLayout?.isRefreshing = false
                    updateSwipeRefreshState()
                }
            }
        }
    }

    @Throws(IOException::class)
    private fun fetchPageData(url: String): Document? {
        return try {
            val cookies = CookieManager.getInstance().getCookie(url)
            Jsoup.connect(url)
                .header("Cookie", cookies ?: "")
                .userAgent(USER_AGENT)
                .timeout(20000)
                .get()
        } catch (e: IOException) {
            Log.e("HomeFragment", "Erro ao buscar dados de $url: ${e.message}")
            null
        }
    }

    private fun parseAllCalendarData(docs: List<Document?>): List<ProvaCalendario> {
        val allExams = mutableListOf<ProvaCalendario>()
        val currentYear = Calendar.getInstance().get(Calendar.YEAR)
        docs.filterNotNull().forEach { doc ->
            val table = doc.selectFirst("table") ?: return@forEach
            for (tr in table.select("tbody > tr")) {
                val cells = tr.children()
                if (cells.size < 5) continue
                try {
                    if (cells[2].text().lowercase().contains("rec")) continue
                    val dataStr = cells[0].text().split(" ")[0]
                    val codigo = cells[1].ownText()
                    val conjuntoStr = cells[3].text().filter { it.isDigit() }
                    if (dataStr.contains('/') && conjuntoStr.isNotEmpty()) {
                        val dataParts = dataStr.split("/")
                        val calendar = Calendar.getInstance().apply {
                            set(currentYear, dataParts[1].toInt() - 1, dataParts[0].toInt(), 0, 0, 0)
                            set(Calendar.MILLISECOND, 0)
                        }
                        allExams.add(ProvaCalendario(calendar, codigo, conjuntoStr.toInt()))
                    }
                } catch (e: Exception) {
                    Log.e("HomeFragment", "Erro ao parsear linha do calendário", e)
                }
            }
        }
        return allExams
    }

    private fun parseAllGradesData(doc: Document?): List<Nota> {
        if (doc == null) return emptyList()
        val allGrades = mutableListOf<Nota>()
        val table = doc.selectFirst("table") ?: return emptyList()
        val conjuntoMap = mutableMapOf<Int, Int>()
        table.select("thead th").forEachIndexed { index, th ->
            if (index > 1) th.text().filter { it.isDigit() }.toIntOrNull()
                ?.let { conjuntoMap[index] = it }
        }
        for (tr in table.select("tbody > tr")) {
            val cols = tr.children()
            if (cols.size <= 1) continue
            val codigo = cols[1].text()
            cols.forEachIndexed { colIndex, td ->
                conjuntoMap[colIndex]?.let { conjunto ->
                    val notaContainer = td.select("div.d-flex.flex-column").firstOrNull {
                        it.selectFirst("span.font-weight-bold")
                            ?.text()?.equals("Nota", ignoreCase = true) == true
                    }
                    val nota = if (notaContainer != null)
                        notaContainer.text().replace("Nota", "", ignoreCase = true).trim()
                    else td.text().trim()
                    if (nota.isNotEmpty()) allGrades.add(Nota(codigo, conjunto, nota))
                }
            }
        }
        return allGrades
    }

    private fun findRecentGrades(
        allExams: List<ProvaCalendario>,
        allGrades: List<Nota>
    ): List<NotaRecente> {
        if (allExams.isEmpty() || allGrades.isEmpty()) return emptyList()
        val gradesMap = allGrades.associateBy { "${it.codigo}-${it.conjunto}" }
        val today = Calendar.getInstance()
        return allExams
            .asSequence()
            .filter { it.data.before(today) || it.data == today }
            .sortedByDescending { it.data }
            .mapNotNull { exam ->
                gradesMap["${exam.codigo}-${exam.conjunto}"]?.let { nota ->
                    if (nota.valor != "--") NotaRecente(exam.codigo, exam.conjunto.toString(), nota.valor, exam.data)
                    else null
                }
            }
            .distinctBy { "${it.codigo}-${it.conjunto}" }
            .take(MAX_RECENT_GRADES)
            .sortedByDescending { it.data }
            .toList()
    }

    // --- UI State ---

    private fun updateUiWithCurrentData() {
        if (isFragmentDestroyed) return
        carouselAdapter.notifyDataSetChanged()
        newsAdapter.notifyDataSetChanged()
        newsSectionContainer?.visibility = if (newsItems.isNotEmpty()) View.VISIBLE else View.GONE
        if (carouselItems.isNotEmpty()) {
            carouselLoadingIndicator?.visibility = View.GONE
            viewPager?.visibility = View.VISIBLE
        } else {
            carouselLoadingIndicator?.visibility = View.VISIBLE
            viewPager?.visibility = View.GONE
        }
        setupRecentGradesTable(recentGradesCache)
    }

    private fun setupRecentGradesTable(grades: List<NotaRecente>) {
        if (isFragmentDestroyed || tableRecentGrades == null) return
        val context = context ?: return
        tableRecentGrades?.removeAllViews()
        if (grades.isEmpty()) { recentGradesSectionContainer?.visibility = View.GONE; return }
        recentGradesSectionContainer?.visibility = View.VISIBLE
        val headerRow = TableRow(context).apply {
            setBackgroundColor(ContextCompat.getColor(context, R.color.header_bg))
            addView(createGradeTableCell("Código", true, context))
            addView(createGradeTableCell("Conjunto", true, context))
            addView(createGradeTableCell("Nota", true, context))
        }
        tableRecentGrades?.addView(headerRow)
        for (grade in grades.sortedByDescending { it.data }) {
            tableRecentGrades?.addView(TableRow(context).apply {
                addView(createGradeTableCell(grade.codigo, false, context))
                addView(createGradeTableCell(grade.conjunto, false, context))
                addView(createGradeTableCell(grade.nota, false, context))
            })
        }
    }

    private fun createGradeTableCell(txt: String, isHeader: Boolean, context: Context): TextView {
        return TextView(context).apply {
            text = txt
            setTypeface(null, if (isHeader) Typeface.BOLD else Typeface.NORMAL)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (isHeader) 13f else 12f)
            val h = (12 * resources.displayMetrics.density).toInt()
            val v = (8 * resources.displayMetrics.density).toInt()
            setPadding(h, v, h, v)
            textAlignment = View.TEXT_ALIGNMENT_CENTER
            setTextColor(ContextCompat.getColor(context, R.color.colorOnSurface))
        }
    }

    fun onLoginSuccess() {
        Log.d("HomeFragment", "Login bem-sucedido - forçando recarregamento")
        clearCache()
        loadInitialData()
    }

    private fun configureCarouselHeight() {
        val viewPager = this.viewPager ?: return
        val context = context ?: return
        if (resources.configuration.screenWidthDp >= 600) return
        val screenWidth = resources.displayMetrics.widthPixels
        val horizontalPadding = resources.getDimensionPixelSize(R.dimen.carousel_margin) * 2 +
                context.resources.getDimensionPixelSize(R.dimen.activity_horizontal_margin)
        val availableWidth = screenWidth - horizontalPadding
        val calculatedHeight = (availableWidth * 300) / 800
        val minHeight = resources.getDimensionPixelSize(R.dimen.carousel_min_height)
        val maxHeight = resources.getDimensionPixelSize(R.dimen.carousel_max_height)
        val finalHeight = calculatedHeight.coerceIn(minHeight, maxHeight)
        val layoutParams = viewPager.layoutParams
        layoutParams.height = finalHeight
        viewPager.layoutParams = layoutParams
    }

    private fun isValidSession(doc: Document?) = doc?.getElementById("home_banners_carousel") != null

    private fun processPageContent(doc: Document?) {
        if (doc == null) return
        val newCarousel = mutableListOf<CarouselItem>()
        val newNews = mutableListOf<NewsItem>()
        processCarousel(doc, newCarousel)
        processNews(doc, newNews)
        carouselItems.clear(); carouselItems.addAll(newCarousel)
        newsItems.clear(); newsItems.addAll(newNews)
    }

    private fun processCarousel(doc: Document, carouselList: MutableList<CarouselItem>) {
        val carousel = doc.getElementById("home_banners_carousel") ?: return
        for (item in carousel.select(".carousel-item")) {
            val linkUrl = item.selectFirst("a")?.attr("href") ?: ""
            var imgUrl = item.select("img").attr("src")
            if (!imgUrl.startsWith("http")) imgUrl = "https://www.colegioetapa.com.br$imgUrl"
            carouselList.add(CarouselItem(imgUrl, linkUrl))
        }
    }

    private fun processNews(doc: Document, newsList: MutableList<NewsItem>) {
        val newsSection = doc.selectFirst("div.col-12.col-lg-8.mb-5") ?: return
        val cards = newsSection.select(".card.border-radius-card")
            .not("#modal-avisos-importantes .card.border-radius-card")

        for (card in cards) {
            val title = card.select("p.text-blue.aviso-text").text()
            val desc = card.select("p.aviso-text").not(".text-blue").text()
            val link = card.select("a[target=_blank]").attr("href")
            if (newsList.none { it.description == desc }) {
                newsList.add(NewsItem(title, desc, link))
            }
        }
    }
    private fun handleInvalidSession() {
        if (isFragmentDestroyed) return
        clearCache()
        navigateToWebView(OUT_URL)
    }

    private fun handleDataFetchError(e: Exception) {
        if (isFragmentDestroyed) return
        Log.e("HomeFragment", "Erro ao buscar dados: ${e.message}", e)
        if (carouselItems.isEmpty() && newsItems.isEmpty() && recentGradesCache.isEmpty()) showOfflineState()
    }

    private fun saveCache() {
        if (isFragmentDestroyed) return
        val context = context ?: return
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            val gson = Gson()
            putString(KEY_CAROUSEL_ITEMS, gson.toJson(carouselItems))
            putString(KEY_NEWS_ITEMS, gson.toJson(newsItems))
            putLong(KEY_CACHE_TIMESTAMP, System.currentTimeMillis())
        }
    }

    private fun saveRecentGradesCache(grades: List<NotaRecente>) {
        if (isFragmentDestroyed) return
        context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)?.edit {
            putString(KEY_RECENT_GRADES, Gson().toJson(grades))
        }
    }

    private fun saveCalendarCache(exams: List<ProvaCalendario>) {
        if (isFragmentDestroyed) return
        context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)?.edit {
            putString(KEY_CALENDAR_EXAMS, Gson().toJson(exams))
            putLong(KEY_CALENDAR_TIMESTAMP, System.currentTimeMillis())
        }
    }

    private fun loadCache(): Boolean {
        if (isFragmentDestroyed) return false
        val context = context ?: return false
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val gson = Gson()
        val recentGradesJson = prefs.getString(KEY_RECENT_GRADES, null)
        if (recentGradesJson != null) {
            recentGradesCache = gson.fromJson<List<NotaRecente>>(
                recentGradesJson, object : TypeToken<List<NotaRecente>>() {}.type
            ).sortedByDescending { it.data }
        }
        val calendarJson = prefs.getString(KEY_CALENDAR_EXAMS, null)
        if (calendarJson != null) {
            calendarExamsCache = gson.fromJson(
                calendarJson, object : TypeToken<List<ProvaCalendario>>() {}.type
            )
        }
        val cacheTimestamp = prefs.getLong(KEY_CACHE_TIMESTAMP, 0)
        if (System.currentTimeMillis() - cacheTimestamp > 24 * 60 * 60 * 1000L) return false
        val carouselJson = prefs.getString(KEY_CAROUSEL_ITEMS, null)
        val newsJson = prefs.getString(KEY_NEWS_ITEMS, null)
        if (carouselJson != null) {
            carouselItems.clear()
            carouselItems.addAll(
                gson.fromJson(carouselJson, object : TypeToken<MutableList<CarouselItem>>() {}.type)
            )
        }
        if (newsJson != null) {
            newsItems.clear()
            newsItems.addAll(
                gson.fromJson(newsJson, object : TypeToken<MutableList<NewsItem>>() {}.type)
            )
        }
        return carouselJson != null || newsJson != null
    }

    private fun clearCache() {
        context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)?.edit { clear() }
        carouselItems.clear(); newsItems.clear()
        recentGradesCache = emptyList(); calendarExamsCache = emptyList()
    }

    private fun navigateToWebView(url: String) {
        if (shouldBlockNavigation || isFragmentDestroyed) return
        try {
            val fragment = WebViewFragment().apply { arguments = createArgs(url) }
            requireActivity().supportFragmentManager.beginTransaction()
                .setCustomAnimations(android.R.anim.fade_in, android.R.anim.fade_out)
                .replace(R.id.nav_host_fragment, fragment).addToBackStack(null)
                .commitAllowingStateLoss()
        } catch (e: IllegalStateException) {
            Log.e("HomeFragment", "Navigation blocked: ${e.message}")
        }
    }

    private fun showLoadingState() {
        if (isFragmentDestroyed) return
        loadingContainer?.visibility = View.VISIBLE
        contentContainer?.visibility = View.GONE
        layoutSemInternet?.visibility = View.GONE
        layoutSafeMode?.visibility = View.GONE
        updateSwipeRefreshState()
    }

    private fun showContentState() {
        if (isFragmentDestroyed) return
        loadingContainer?.visibility = View.GONE
        contentContainer?.visibility = View.VISIBLE
        layoutSemInternet?.visibility = View.GONE
        layoutSafeMode?.visibility = View.GONE
        updateSwipeRefreshState()
    }

    private fun showOfflineState() {
        if (isFragmentDestroyed) return
        loadingContainer?.visibility = View.GONE
        contentContainer?.visibility = View.GONE
        layoutSemInternet?.visibility = View.VISIBLE
        layoutSafeMode?.visibility = View.GONE
        updateSwipeRefreshState()
    }

    private fun showSafeModeState() {
        if (isFragmentDestroyed) return
        loadingContainer?.visibility = View.GONE
        contentContainer?.visibility = View.GONE
        layoutSemInternet?.visibility = View.GONE
        layoutSafeMode?.visibility = View.VISIBLE
        updateSwipeRefreshState()
    }

    private fun hasInternetConnection(): Boolean {
        val cm = context?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    // --- Adapters ---

    private inner class CarouselAdapter : RecyclerView.Adapter<CarouselViewHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            CarouselViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_carousel, parent, false))
        override fun onBindViewHolder(holder: CarouselViewHolder, position: Int) {
            val item = carouselItems[position]
            Glide.with(holder.itemView.context).load(item.imageUrl).centerCrop().into(holder.imageView)
            holder.itemView.setOnClickListener { item.linkUrl?.let { navigateToWebView(it) } }
        }
        override fun getItemCount() = carouselItems.size
    }

    private inner class NewsAdapter : RecyclerView.Adapter<NewsViewHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            NewsViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.news_item, parent, false))

        override fun onBindViewHolder(holder: NewsViewHolder, position: Int) {
            val item = newsItems[position]
            holder.icon.setImageResource(R.drawable.icone_etapa)
            holder.title.text = item.title
            holder.description.text = item.description
            holder.itemView.setOnClickListener {
                item.link?.let { navigateToWebView(it) }
            }
        }

        override fun getItemCount() = newsItems.size
    }

    internal class CarouselViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val imageView: ImageView = itemView.findViewById(R.id.imageView)
    }
    internal class NewsViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.news_icon)
        val title: TextView = view.findViewById(R.id.news_title)
        val description: TextView = view.findViewById(R.id.news_description)
    }
    data class CarouselItem(val imageUrl: String?, val linkUrl: String?)

    data class NewsItem(
        val title: String?,
        val description: String?,
        val link: String?
    )
}