package com.marinov.openfei

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
import android.widget.FrameLayout
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
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdLoader
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.nativead.MediaView
import com.google.android.gms.ads.nativead.NativeAd
import com.google.android.gms.ads.nativead.NativeAdOptions
import com.google.android.gms.ads.nativead.NativeAdView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
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

    // ── AdMob ──────────────────────────────────────────────────
    // adSection: wrapper LinearLayout que contém o título "Anúncio" + adContainer.
    //            É ele quem some/aparece como seção inteira.
    // adContainer: FrameLayout slot onde o NativeAdView é inserido via código.
    private var adSection: LinearLayout? = null
    private var adContainer: FrameLayout? = null
    private var currentNativeAd: NativeAd? = null
    // ────────────────────────────────────────────────────────────

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

        // ── AdMob ──────────────────────────────────────────────
        const val AD_UNIT_ID = "ca-app-pub-8734981142486691/1921511395"
        // ────────────────────────────────────────────────────────
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
        // Destrói o anúncio nativo para evitar memory leak
        currentNativeAd?.destroy()
        currentNativeAd = null

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

        // ── AdMob ──────────────────────────────────────────────
        adSection = view.findViewById(R.id.adSection)
        adContainer = view.findViewById(R.id.adContainer)
        // ────────────────────────────────────────────────────────
    }

    private fun setupAdapters() {
        carouselAdapter = CarouselAdapter()
        viewPager?.adapter = carouselAdapter
    }

    private fun setupListeners() {
        btnTentarNovamente?.setOnClickListener { loadInitialData() }

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

    private fun loadInitialData() {
        // Exibe cache do carousel imediatamente (sem esperar a rede)
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
            // Passo 1: carrega notas, aulas e calendário do cache local antes de qualquer chamada
            // de rede, evitando o "piscar" onde as seções somem enquanto aguardam o servidor.
            val cachedNotas  = withContext(Dispatchers.IO) { Dados.obterNotas(online = false) }
            val cachedAulas  = withContext(Dispatchers.IO) { Dados.retornaAulasDia(online = false) }
            val cachedProvas = withContext(Dispatchers.IO) { Dados.obterCalendarioProvasCache() }

            if (!isFragmentDestroyed) {
                val hasCachedData = hasCarouselCache || cachedNotas.isNotEmpty() || cachedAulas.isNotEmpty()
                if (hasCachedData) {
                    // Preenche UI com dados em cache — serão sobrepostos silenciosamente pelo fetch
                    setupNotasTable(cachedNotas, cachedProvas)
                    setupAulasDia(cachedAulas)
                    showContentState()
                } else {
                    // Nenhum dado em cache: mostra tela de carregamento normal
                    showLoadingState()
                }
            }

            // Passo 2: verifica conectividade/sessão (suspende aqui até a resposta)
            val status = mainActivity.checkConnectionAndSession()

            if (isFragmentDestroyed) return@launch

            when (status) {
                MainActivity.STATUS_LOGIN_NEEDED -> {
                    // Caso A: LoginActivity já foi lançada pela MainActivity; não há nada a fazer.
                    return@launch
                }
                MainActivity.STATUS_OFFLINE -> {
                    // Caso C: sem conexão → dados em cache já estão exibidos; apenas sinaliza offline.
                    showOfflineState()
                }
                MainActivity.STATUS_ONLINE_OK -> {
                    // Caso B: online + logado → busca dados atualizados do servidor.
                    // O conteúdo em cache já está visível; fetchDataFromServer atualiza em segundo plano.
                    fetchDataFromServer()
                }
            }
        }
    }

    /**
     * Busca dados do servidor em paralelo usando supervisorScope para isolar falhas.
     * Só deve ser chamado quando o status já for STATUS_ONLINE_OK.
     */
    private fun fetchDataFromServer() {
        if (contentContainer?.visibility == View.VISIBLE) {
            topLoadingBar?.visibility = View.VISIBLE
        }

        val mainActivity = activity as? MainActivity ?: return

        lifecycleScope.launch {
            try {
                val result = supervisorScope {
                    val carrosselDeferred = async(Dispatchers.IO) { fetchPageData(HOME_URL) }
                    val notasDeferred     = async { Dados.obterNotas(online = true) }
                    val aulasDeferred     = async { Dados.retornaAulasDia(online = true) }
                    val provasDeferred    = async { Dados.obterCalendarioProvas(online = true) }

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

    private fun setupNotasTable(notas: List<Dados.Nota>, provas: List<Dados.ProvaCalendario>) {
        if (isFragmentDestroyed || tableRecentGrades == null) return
        val context = context ?: return
        tableRecentGrades?.removeAllViews()

        val notasPreenchidas = notas.filter { it.valor.isNotEmpty() }
        if (notasPreenchidas.isEmpty()) {
            recentGradesSectionContainer?.visibility = View.GONE
            return
        }

        val sortedNotas = Dados.ordenarNotasParaHome(notasPreenchidas, provas)
        recentGradesSectionContainer?.visibility = View.VISIBLE

        val headerRow = TableRow(context).apply {
            setBackgroundColor(ContextCompat.getColor(context, R.color.header_bg))
            addView(createTableCell("Disciplina", true, context))
            addView(createTableCell("Prova", true, context))
            addView(createTableCell("Nota", true, context))
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

    private fun setupAulasDia(aulas: List<Dados.Aula>) {
        if (isFragmentDestroyed) return
        val context = context ?: return

        aulasContainer?.removeAllViews()

        if (aulas.isEmpty()) {
            aulasSectionContainer?.visibility = View.GONE
            return
        }

        aulasSectionContainer?.visibility = View.VISIBLE
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
        // Carrega o anúncio apenas uma vez por ciclo de vida da view
        if (currentNativeAd == null) loadNativeAd()
    }

    private fun showOfflineState() {
        if (isFragmentDestroyed) return
        loadingContainer?.visibility = View.GONE
        contentContainer?.visibility = View.GONE
        layoutSemInternet?.visibility = View.VISIBLE
        updateSwipeRefreshState()
    }

    // ── AdMob: carregamento e binding do anúncio nativo ────────

    private fun loadNativeAd() {
        val context = context ?: return
        val adLoader = AdLoader.Builder(context, AD_UNIT_ID)
            .forNativeAd { nativeAd ->
                // Destrói eventual anúncio anterior
                currentNativeAd?.destroy()
                currentNativeAd = nativeAd

                if (isFragmentDestroyed || !isAdded) {
                    nativeAd.destroy()
                    currentNativeAd = null
                    return@forNativeAd
                }

                val adView = layoutInflater.inflate(
                    R.layout.ad_home_native, adContainer, false
                ) as NativeAdView

                // Vincula headline (obrigatório)
                val headlineView = adView.findViewById<TextView>(R.id.ad_headline)
                headlineView.text = nativeAd.headline
                adView.headlineView = headlineView

                // Body
                val bodyView = adView.findViewById<TextView>(R.id.ad_body)
                bodyView.text = nativeAd.body
                bodyView.visibility = if (nativeAd.body != null) View.VISIBLE else View.GONE
                adView.bodyView = bodyView

                // Ícone
                val iconView = adView.findViewById<ImageView>(R.id.ad_icon)
                nativeAd.icon?.let {
                    iconView.setImageDrawable(it.drawable)
                    iconView.visibility = View.VISIBLE
                } ?: run { iconView.visibility = View.GONE }
                adView.iconView = iconView

                // MediaView
                val mediaView = adView.findViewById<MediaView>(R.id.ad_media)
                adView.mediaView = mediaView

                // Call-to-action
                val ctaView = adView.findViewById<MaterialButton>(R.id.ad_call_to_action)
                nativeAd.callToAction?.let {
                    ctaView.text = it
                    ctaView.visibility = View.VISIBLE
                } ?: run { ctaView.visibility = View.GONE }
                adView.callToActionView = ctaView

                // Registra o NativeAd na view (obrigatório para rastrear cliques)
                adView.setNativeAd(nativeAd)

                adContainer?.removeAllViews()
                adContainer?.addView(adView)
                // Revela a seção inteira (título "Anúncio" + card do anúncio)
                adSection?.visibility = View.VISIBLE
            }
            .withAdListener(object : AdListener() {
                override fun onAdFailedToLoad(error: LoadAdError) {
                    Log.w("HomeFragment", "Anúncio nativo falhou ao carregar: ${error.message}")
                    // Esconde a seção inteira para não deixar o título órfão
                    adSection?.visibility = View.GONE
                }
            })
            .withNativeAdOptions(NativeAdOptions.Builder().build())
            .build()

        adLoader.loadAd(AdRequest.Builder().build())
    }

    // ────────────────────────────────────────────────────────────

    // ======================== ADAPTER ========================
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