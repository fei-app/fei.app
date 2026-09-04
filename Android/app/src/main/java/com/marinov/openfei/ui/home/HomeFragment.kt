package com.marinov.openfei.ui.home

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Typeface
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
import androidx.core.net.toUri
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.viewpager2.widget.ViewPager2
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.load.model.LazyHeaders
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.request.target.Target
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.marinov.openfei.R
import com.marinov.openfei.data.Aula
import com.marinov.openfei.data.AulasRepository
import com.marinov.openfei.data.CalendarioRepository
import com.marinov.openfei.data.Nota
import com.marinov.openfei.data.NotasRepository
import com.marinov.openfei.data.ProvaCalendario
import com.marinov.openfei.data.SessionExpiredException
import com.marinov.openfei.ui.main.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.IOException

class HomeFragment : Fragment() {

    private var swipeRefreshLayout: SwipeRefreshLayout? = null
    private var viewPager: ViewPager2? = null
    private var layoutSemInternet: LinearLayout? = null
    private var btnTentarNovamente: MaterialButton? = null
    private var loadingContainer: View? = null
    private var contentContainer: View? = null
    private var txtStuckHint: TextView? = null
    private var carouselLoadingIndicator: CircularProgressIndicator? = null
    private var recentGradesSectionContainer: View? = null
    private var tableRecentGrades: TableLayout? = null
    private var topLoadingBar: View? = null
    private var aulasSectionContainer: View? = null
    private var aulasContainer: LinearLayout? = null
    private var txtSemAulas: TextView? = null

    // Novas views para estado vazio e botões "Ver mais"
    private var txtSemNotas: TextView? = null
    private var cardRecentGrades: MaterialCardView? = null
    private var btnVerMaisNotas: MaterialButton? = null
    private var btnVerMaisAulas: MaterialButton? = null

    private lateinit var carouselAdapter: CarouselAdapter
    private var isFragmentDestroyed = false
    private val carouselItems: MutableList<CarouselItem> = mutableListOf()
    private val handler = Handler(Looper.getMainLooper())

    private companion object {
        const val PREFS_NAME = "HomeFragmentCache"
        const val KEY_CAROUSEL_ITEMS = "carousel_items"
        const val KEY_CACHE_TIMESTAMP = "cache_timestamp"
        const val HOME_URL = "https://interage.fei.org.br/secureserver/portal/graduacao/home"
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android 16; sdk_gphone64_x86_64 Build/BE2A.250530.026.D1; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/133.0.6943.137 Mobile Safari/537.36"
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_home, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        isFragmentDestroyed = false
        initializeViews(view)
        setupAdapters()
        setupListeners()
        configureCarouselHeight()
        loadInitialData()
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
        txtStuckHint = view.findViewById(R.id.txtStuckHint)
        carouselLoadingIndicator = view.findViewById(R.id.carouselLoadingIndicator)
        recentGradesSectionContainer = view.findViewById(R.id.recentGradesSectionContainer)
        tableRecentGrades = view.findViewById(R.id.tableRecentGrades)
        topLoadingBar = view.findViewById(R.id.top_loading_bar)
        aulasSectionContainer = view.findViewById(R.id.aulasSectionContainer)
        aulasContainer = view.findViewById(R.id.aulasContainer)
        txtSemAulas = view.findViewById(R.id.txtSemAulas)

        // Inicializando novas views
        txtSemNotas = view.findViewById(R.id.txtSemNotas)
        cardRecentGrades = view.findViewById(R.id.cardRecentGrades)
        btnVerMaisNotas = view.findViewById(R.id.btnVerMaisNotas)
        btnVerMaisAulas = view.findViewById(R.id.btnVerMaisAulas)
    }

    private fun setupAdapters() {
        carouselAdapter = CarouselAdapter()
        viewPager?.adapter = carouselAdapter
    }

    private fun setupListeners() {
        btnTentarNovamente?.setOnClickListener { loadInitialData() }

        btnVerMaisNotas?.setOnClickListener {
            (activity as? MainActivity)?.openFragment(R.id.navigation_notas)
        }

        btnVerMaisAulas?.setOnClickListener {
            (activity as? MainActivity)?.openFragment(R.id.option_horarios_aula)
        }

        swipeRefreshLayout?.setOnRefreshListener {
            if (isAdded && !isFragmentDestroyed && contentContainer?.visibility == View.VISIBLE &&
                layoutSemInternet?.visibility != View.VISIBLE
            ) {
                fetchDataFromServer()
            } else {
                swipeRefreshLayout?.isRefreshing = false
            }
        }
    }

    private fun updateSwipeRefreshState() {
        if (isFragmentDestroyed || swipeRefreshLayout == null) return
        val isNormalContent = contentContainer?.visibility == View.VISIBLE &&
                layoutSemInternet?.visibility != View.VISIBLE
        swipeRefreshLayout?.isEnabled = isNormalContent
        if (!isNormalContent) {
            swipeRefreshLayout?.isRefreshing = false
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun loadInitialData() {
        val hasCarouselCache = loadCarouselCache()
        carouselAdapter.notifyDataSetChanged()

        if (carouselItems.isNotEmpty()) {
            carouselLoadingIndicator?.visibility = View.GONE
            viewPager?.visibility = View.VISIBLE
        } else {
            carouselLoadingIndicator?.visibility = View.VISIBLE
            viewPager?.visibility = View.GONE
        }

        val mainActivity = activity as? MainActivity ?: return

        lifecycleScope.launch {
            val cachedNotas  = withContext(Dispatchers.IO) { NotasRepository.obterNotas(online = false) }
            val cachedAulas  = withContext(Dispatchers.IO) { AulasRepository.retornaAulasDia(online = false) }
            val cachedProvas = withContext(Dispatchers.IO) { CalendarioRepository.obterCalendarioProvasCache() }

            if (!isFragmentDestroyed) {
                val hasCachedData = hasCarouselCache || cachedNotas.isNotEmpty() || cachedAulas.isNotEmpty()
                if (hasCachedData) {
                    setupNotasTable(cachedNotas, cachedProvas)
                    setupAulasDia(cachedAulas)
                    showContentState()
                } else {
                    showLoadingState()
                }
            }

            val status = mainActivity.checkConnectionAndSession()
            if (isFragmentDestroyed) return@launch

            when (status) {
                MainActivity.STATUS_LOGIN_NEEDED -> { return@launch }
                MainActivity.STATUS_OFFLINE -> { showOfflineState() }
                MainActivity.STATUS_ONLINE_OK -> { fetchDataFromServer() }
            }
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun fetchDataFromServer() {
        if (contentContainer?.visibility == View.VISIBLE) {
            topLoadingBar?.visibility = View.VISIBLE
        }

        val mainActivity = activity as? MainActivity ?: return

        lifecycleScope.launch {
            try {
                val result = supervisorScope {
                    val carrosselDeferred = async(Dispatchers.IO) { fetchPageData(HOME_URL) }
                    val notasDeferred     = async { NotasRepository.obterNotas(online = true) }
                    val aulasDeferred     = async { AulasRepository.retornaAulasDia(online = true) }
                    val provasDeferred    = async { CalendarioRepository.obterCalendarioProvas(online = true) }

                    try {
                        val carousel = carrosselDeferred.await()
                        val notas    = notasDeferred.await()
                        val aulas    = aulasDeferred.await()
                        val provas   = provasDeferred.await()
                        Triple(carousel, notas, Pair(aulas, provas))
                    } catch (e: SessionExpiredException) {
                        coroutineContext.cancelChildren()
                        throw e
                    }
                }

                if (isFragmentDestroyed) return@launch

                val (homeDoc, notas, aulasPair) = result
                val (aulasDia, provas) = aulasPair

                if (homeDoc != null) {
                    processPageContent(homeDoc)
                    saveCarouselCache()

                    if (!isFragmentDestroyed) {
                        showContentState()
                        carouselAdapter.notifyDataSetChanged()

                        if (carouselItems.isNotEmpty()) {
                            carouselLoadingIndicator?.visibility = View.GONE
                            viewPager?.visibility = View.VISIBLE
                        }

                        setupNotasTable(notas, provas)
                        setupAulasDia(aulasDia)
                    }
                } else {
                    Log.e("HomeFragment", "Página home retornou null")
                    if (!isFragmentDestroyed) handleDataFetchError()
                }
            } catch (_: SessionExpiredException) {
                Log.w("HomeFragment", "Sessão expirada durante fetch")
                withContext(Dispatchers.Main) {
                    if (!isFragmentDestroyed) mainActivity.checkConnectionAndSession()
                }
            } catch (e: Exception) {
                Log.e("HomeFragment", "Erro ao buscar dados: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    if (!isFragmentDestroyed) handleDataFetchError()
                }
            } finally {
                withContext(Dispatchers.Main) {
                    topLoadingBar?.visibility = View.GONE
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
            if (cookies.isNullOrBlank()) return null
            Jsoup.connect(url)
                .header("Cookie", cookies)
                .userAgent(USER_AGENT)
                .timeout(20000)
                .get()
        } catch (e: IOException) {
            Log.e("HomeFragment", "Erro ao buscar $url: ${e.message}")
            null
        }
    }

    private fun setupNotasTable(notas: List<Nota>, provas: List<ProvaCalendario>) {
        if (isFragmentDestroyed || tableRecentGrades == null) return
        val context = context ?: return

        tableRecentGrades?.removeAllViews()

        // A seção agora é sempre visível
        recentGradesSectionContainer?.visibility = View.VISIBLE

        val notasPreenchidas = notas.filter { it.valor.isNotEmpty() }
        if (notasPreenchidas.isEmpty()) {
            // Se não há notas, oculta o card/tabela e mostra o texto de estado vazio
            cardRecentGrades?.visibility = View.GONE
            txtSemNotas?.visibility = View.VISIBLE
            return
        }

        // Se há notas, mostra o card/tabela e oculta o texto de estado vazio
        cardRecentGrades?.visibility = View.VISIBLE
        txtSemNotas?.visibility = View.GONE

        val sortedNotas = NotasRepository.ordenarNotasParaHome(notasPreenchidas, provas)

        val headerRow = TableRow(context).apply {
            setBackgroundColor(ContextCompat.getColor(context, R.color.header_bg))
            addView(createTableCell(getString(R.string.home_coluna_disciplina), true, context))
            addView(createTableCell(getString(R.string.home_coluna_prova), true, context))
            addView(createTableCell(getString(R.string.home_coluna_nota), true, context))
        }
        tableRecentGrades?.addView(headerRow)

        for (nota in sortedNotas.take(6)) {
            tableRecentGrades?.addView(TableRow(context).apply {
                addView(createTableCell(nota.nomeDisciplina, false, context))
                addView(createTableCell(nota.tipoProva, false, context))
                addView(createTableCell(nota.valor, false, context))
            })
        }
    }

    @SuppressLint("SetTextI18n")
    private fun setupAulasDia(aulas: List<Aula>) {
        if (isFragmentDestroyed) return
        val context = context ?: return

        aulasContainer?.removeAllViews()

        // A seção agora é sempre visível
        aulasSectionContainer?.visibility = View.VISIBLE

        if (aulas.isEmpty()) {
            // Se não há aulas, oculta o container dos cards e mostra o texto de estado vazio
            aulasContainer?.visibility = View.GONE
            txtSemAulas?.visibility = View.VISIBLE
            return
        }

        // Se há aulas, mostra o container e oculta o texto de estado vazio
        aulasContainer?.visibility = View.VISIBLE
        txtSemAulas?.visibility = View.GONE

        for (aula in aulas) {
            val card = LayoutInflater.from(context).inflate(R.layout.item_aula_card, aulasContainer, false) as MaterialCardView
            card.findViewById<TextView>(R.id.txtAulaDisciplina).text = aula.nomeDisciplina
            card.findViewById<TextView>(R.id.txtAulaHorario).text = "${aula.horaInicio} - ${aula.horaFim}"
            card.findViewById<TextView>(R.id.txtAulaSala).text = aula.sala
            aulasContainer?.addView(card)
        }
    }

    private fun createTableCell(txt: String, isHeader: Boolean, context: Context): TextView {
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

        viewPager.layoutParams = viewPager.layoutParams.apply { height = finalHeight }
    }

    private fun processPageContent(doc: Document?) {
        if (doc == null) return
        val newCarousel = mutableListOf<CarouselItem>()

        for (item in doc.select("#carousel-example-generic .item")) {
            val linkHref = item.selectFirst("a")?.attr("href") ?: continue
            val imgSrc = item.selectFirst("img")?.attr("src") ?: continue
            val absoluteImageUrl = if (imgSrc.startsWith("http")) imgSrc else "https://interage.fei.org.br$imgSrc"
            newCarousel.add(CarouselItem(absoluteImageUrl, linkHref))
        }

        carouselItems.clear()
        carouselItems.addAll(newCarousel)
    }

    private fun handleDataFetchError() {
        if (isFragmentDestroyed) return
        if (carouselItems.isEmpty()) showOfflineState()
    }

    private fun saveCarouselCache() {
        if (isFragmentDestroyed) return
        context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)?.edit {
            putString(KEY_CAROUSEL_ITEMS, Gson().toJson(carouselItems))
            putLong(KEY_CACHE_TIMESTAMP, System.currentTimeMillis())
        }
    }

    private fun loadCarouselCache(): Boolean {
        if (isFragmentDestroyed) return false
        val prefs = context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) ?: return false
        if (System.currentTimeMillis() - prefs.getLong(KEY_CACHE_TIMESTAMP, 0) > 24 * 60 * 60 * 1000L) return false
        val json = prefs.getString(KEY_CAROUSEL_ITEMS, null) ?: return false
        val type = object : TypeToken<MutableList<CarouselItem>>() {}.type
        carouselItems.clear()
        carouselItems.addAll(Gson().fromJson(json, type))
        return true
    }

    private fun showLoadingState() {
        if (isFragmentDestroyed) return
        loadingContainer?.visibility = View.VISIBLE
        contentContainer?.visibility = View.GONE
        layoutSemInternet?.visibility = View.GONE
        updateSwipeRefreshState()
    }

    private fun showContentState() {
        if (isFragmentDestroyed) return
        loadingContainer?.visibility = View.GONE
        contentContainer?.visibility = View.VISIBLE
        layoutSemInternet?.visibility = View.GONE
        updateSwipeRefreshState()
    }

    private fun showOfflineState() {
        if (isFragmentDestroyed) return
        loadingContainer?.visibility = View.GONE
        contentContainer?.visibility = View.GONE
        layoutSemInternet?.visibility = View.VISIBLE
        updateSwipeRefreshState()
    }

    private inner class CarouselAdapter : RecyclerView.Adapter<CarouselViewHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CarouselViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_carousel, parent, false)
            return CarouselViewHolder(view)
        }

        override fun onBindViewHolder(holder: CarouselViewHolder, position: Int) {
            val item = carouselItems[position]
            holder.imageView.scaleType = ImageView.ScaleType.FIT_XY

            val domainCookies = CookieManager.getInstance().getCookie("https://interage.fei.org.br")
            val headersBuilder = LazyHeaders.Builder().addHeader("User-Agent", USER_AGENT)
            if (!domainCookies.isNullOrEmpty()) headersBuilder.addHeader("Cookie", domainCookies)

            val glideUrl = GlideUrl(item.imageUrl, headersBuilder.build())
            val requestOptions = RequestOptions().diskCacheStrategy(DiskCacheStrategy.NONE).timeout(15000)

            Glide.with(holder.itemView.context)
                .asBitmap()
                .load(glideUrl)
                .apply(requestOptions)
                .listener(object : RequestListener<android.graphics.Bitmap> {
                    override fun onLoadFailed(e: GlideException?, model: Any?, target: Target<android.graphics.Bitmap>, isFirstResource: Boolean) = false
                    override fun onResourceReady(resource: android.graphics.Bitmap, model: Any?, target: Target<android.graphics.Bitmap>?, dataSource: DataSource, isFirstResource: Boolean) = false
                })
                .into(holder.imageView)

            holder.itemView.setOnClickListener {
                item.linkUrl?.let { link ->
                    try { startActivity(Intent(Intent.ACTION_VIEW, link.toUri())) }
                    catch (e: Exception) { Log.e("HomeFragment", "Erro ao abrir link: $link", e) }
                }
            }
        }

        override fun getItemCount() = carouselItems.size
    }

    internal class CarouselViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val imageView: ImageView = itemView.findViewById(R.id.imageView)
    }

    data class CarouselItem(val imageUrl: String?, val linkUrl: String?)
}