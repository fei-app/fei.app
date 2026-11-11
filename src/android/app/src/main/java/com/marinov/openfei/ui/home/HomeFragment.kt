package com.marinov.openfei.ui.home

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Typeface
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import androidx.core.content.ContextCompat
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
import com.bumptech.glide.signature.ObjectKey
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.marinov.openfei.R
import com.marinov.openfei.background.SyncScheduler
import com.marinov.openfei.data.Aula
import com.marinov.openfei.data.AulasRepository
import com.marinov.openfei.data.CarouselItem
import com.marinov.openfei.data.HomeRepository
import com.marinov.openfei.data.Nota
import com.marinov.openfei.data.NotasRepository
import com.marinov.openfei.data.SessionExpiredException
import com.marinov.openfei.login.SessionManager
import com.marinov.openfei.ui.main.MainActivity
import com.marinov.openfei.util.WebViewHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext

class HomeFragment : Fragment() {

    private companion object {
        const val TAG = "HomeFragment"
        const val HOME_URL = "https://interage.fei.org.br/secureserver/portal/graduacao/home"
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 16; sdk_gphone64_x86_64 Build/BE2A.250530.026.D1; wv) " +
                    "AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/133.0.6943.137 Mobile Safari/537.36"
        @Volatile
        private var immediateSyncRequested = false
    }
    private var swipeRefreshLayout: SwipeRefreshLayout? = null
    private var viewPager: ViewPager2? = null
    private var layoutSemInternet: LinearLayout? = null
    private var btnTentarNovamente: MaterialButton? = null
    private var loadingContainer: View? = null
    private var contentContainer: View? = null
    private var carouselLoadingIndicator: CircularProgressIndicator? = null
    private var recentGradesSectionContainer: View? = null
    private var tableRecentGrades: TableLayout? = null
    private var topLoadingBar: View? = null
    private var aulasSectionContainer: View? = null
    private var aulasContainer: LinearLayout? = null
    private var txtSemAulas: TextView? = null
    private var txtSemNotas: TextView? = null
    private var cardRecentGrades: MaterialCardView? = null
    private var btnVerMaisNotas: MaterialButton? = null
    private var btnVerMaisAulas: MaterialButton? = null

    private lateinit var carouselAdapter: CarouselAdapter
    private var isFragmentDestroyed = false
    private val carouselItems: MutableList<CarouselItem> = mutableListOf()

    /** ★ BUG 66: cookie lido UMA vez em IO, em vez de a cada bind na main thread. */
    private var carouselCookieHeader: String? = null

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
        ensureSyncScheduled()
        loadInitialData()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        configureCarouselHeight()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        isFragmentDestroyed = true
    }

    /** ★ Nova arquitetura: garante os agendamentos e dispara sync imediato na primeira vez. */
    /**
     * ★ Apenas garante os agendamentos (AlarmManager + WorkManager).
     * Não lê cache nem chama função suspend aqui — isso é feito em
     * loadInitialData(), que já tem os dados em memória e roda em IO.
     */
    private fun ensureSyncScheduled() {
        val appContext = context?.applicationContext ?: return
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            runCatching { SyncScheduler.scheduleAll(appContext) }
                .onFailure { Log.e(TAG, "Erro ao agendar sincronização", it) }
        }
    }

    /**
     * ★ Primeira execução com base vazia → dispara um sync imediato.
     * Chamado a partir de loadInitialData(), reaproveitando o cache já lido.
     */
    private fun maybeRequestImmediateSync(hasCachedData: Boolean) {
        if (hasCachedData || immediateSyncRequested) return
        val appContext = context?.applicationContext ?: return
        immediateSyncRequested = true
        runCatching { SyncScheduler.syncNow(appContext, SyncScheduler.TYPE_BOOT) }
            .onFailure {
                immediateSyncRequested = false
                Log.e(TAG, "Erro ao disparar sincronização imediata", it)
            }
    }

    private fun initializeViews(view: View) {
        swipeRefreshLayout = view.findViewById(R.id.swipe_refresh_layout)
        loadingContainer = view.findViewById(R.id.loadingContainer)
        contentContainer = view.findViewById(R.id.contentContainer)
        layoutSemInternet = view.findViewById(R.id.layout_sem_internet)
        btnTentarNovamente = view.findViewById(R.id.btn_tentar_novamente)
        viewPager = view.findViewById(R.id.viewPager)
        carouselLoadingIndicator = view.findViewById(R.id.carouselLoadingIndicator)
        recentGradesSectionContainer = view.findViewById(R.id.recentGradesSectionContainer)
        tableRecentGrades = view.findViewById(R.id.tableRecentGrades)
        topLoadingBar = view.findViewById(R.id.top_loading_bar)
        aulasSectionContainer = view.findViewById(R.id.aulasSectionContainer)
        aulasContainer = view.findViewById(R.id.aulasContainer)
        txtSemAulas = view.findViewById(R.id.txtSemAulas)
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
        if (!isNormalContent) swipeRefreshLayout?.isRefreshing = false
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun loadInitialData() {
        val mainActivity = activity as? MainActivity ?: return
        val context = context ?: return

        viewLifecycleOwner.lifecycleScope.launch {
            // 1) Cache primeiro (UI imediata) — tudo em IO
            val cached = withContext(Dispatchers.IO) {
                Triple(
                    HomeRepository.obterCarrossel(context, online = false),
                    NotasRepository.obterNotas(online = false),
                    AulasRepository.retornaAulasDia(online = false)
                )
            }
            if (!isAdded || isFragmentDestroyed) return@launch

            val (cachedCarousel, cachedNotas, cachedAulas) = cached
            carouselItems.clear()
            carouselItems.addAll(cachedCarousel)
            carouselAdapter.notifyDataSetChanged()
            updateCarouselVisibility()

            val hasCachedData =
                cachedCarousel.isNotEmpty() || cachedNotas.isNotEmpty() || cachedAulas.isNotEmpty()

            maybeRequestImmediateSync(hasCachedData)   // ★ NOVO

            if (hasCachedData) {
                setupNotasTable(cachedNotas)
                setupAulasDia(cachedAulas)
                showContentState()
            } else {
                showLoadingState()
            }

            // 2) Status de sessão/rede
            val status = mainActivity.checkConnectionAndSession()
            if (!isAdded || isFragmentDestroyed) return@launch

            when (status) {
                SessionManager.STATUS_LOGIN_NEEDED -> return@launch
                SessionManager.STATUS_OFFLINE -> showOfflineState()
                SessionManager.STATUS_ONLINE_OK -> fetchDataFromServer()
            }
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun fetchDataFromServer() {
        if (contentContainer?.visibility == View.VISIBLE) topLoadingBar?.visibility = View.VISIBLE

        val mainActivity = activity as? MainActivity ?: return
        val context = context ?: return

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val result = supervisorScope {
                    // ★ BUG 48: tudo em Dispatchers.IO (antes notas/aulas rodavam na Main,
                    // gravando arquivo de cache na UI thread).
                    val carrosselDeferred = async(Dispatchers.IO) {
                        HomeRepository.obterCarrossel(context, online = true)
                    }
                    val notasDeferred = async(Dispatchers.IO) {
                        NotasRepository.obterNotas(online = true)
                    }
                    val aulasDeferred = async(Dispatchers.IO) {
                        AulasRepository.retornaAulasDia(online = true)
                    }
                    val cookiesDeferred = async(Dispatchers.IO) {
                        WebViewHelper.getCookiesSafely(HOME_URL).takeIf { it.isNotEmpty() }
                    }

                    try {
                        val carousel = carrosselDeferred.await()
                        val notas = notasDeferred.await()
                        val aulas = aulasDeferred.await()
                        val cookies = cookiesDeferred.await()
                        object {
                            val carousel = carousel
                            val notas = notas
                            val aulas = aulas
                            val cookies = cookies
                        }
                    } catch (e: SessionExpiredException) {
                        coroutineContext.cancelChildren()
                        throw e
                    }
                }

                if (!isAdded || isFragmentDestroyed) return@launch

                carouselCookieHeader = result.cookies

                val carouselMudou = result.carousel != carouselItems
                if (carouselMudou) {
                    carouselItems.clear()
                    carouselItems.addAll(result.carousel)
                    carouselAdapter.notifyDataSetChanged()
                }
                // ★ BUG 49: o indicador some sempre ao concluir, mesmo sem itens.
                updateCarouselVisibility()

                setupNotasTable(result.notas)
                setupAulasDia(result.aulas)

                if (carouselItems.isEmpty() && result.notas.isEmpty() && result.aulas.isEmpty()) {
                    handleDataFetchError()
                } else {
                    showContentState()
                }
            } catch (_: SessionExpiredException) {
                Log.w(TAG, "Sessão expirada durante fetch")
                withContext(Dispatchers.Main) {
                    if (isAdded && !isFragmentDestroyed) mainActivity.checkConnectionAndSession()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Erro ao buscar dados", e)
                withContext(Dispatchers.Main) {
                    if (isAdded && !isFragmentDestroyed) handleDataFetchError()
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

    private fun updateCarouselVisibility() {
        if (isFragmentDestroyed) return
        if (carouselItems.isNotEmpty()) {
            carouselLoadingIndicator?.visibility = View.GONE
            viewPager?.visibility = View.VISIBLE
        } else {
            carouselLoadingIndicator?.visibility = View.GONE
            viewPager?.visibility = View.GONE
        }
    }

    private fun setupNotasTable(notas: List<Nota>) {
        if (isFragmentDestroyed || tableRecentGrades == null) return
        val context = context ?: return

        tableRecentGrades?.removeAllViews()
        recentGradesSectionContainer?.visibility = View.VISIBLE

        val notasPreenchidas = notas.filter { it.valor.isNotEmpty() }
        if (notasPreenchidas.isEmpty()) {
            cardRecentGrades?.visibility = View.GONE
            txtSemNotas?.visibility = View.VISIBLE
            return
        }

        cardRecentGrades?.visibility = View.VISIBLE
        txtSemNotas?.visibility = View.GONE

        tableRecentGrades?.addView(
            TableRow(context).apply {
                setBackgroundColor(ContextCompat.getColor(context, R.color.header_bg))
                addView(createTableCell(getString(R.string.home_coluna_disciplina), true, context))
                addView(createTableCell(getString(R.string.home_coluna_prova), true, context))
                addView(createTableCell(getString(R.string.home_coluna_nota), true, context))
            }
        )

        for ((_, nomeDisciplina, tipoProva, valor) in notasPreenchidas.take(3)) {
            tableRecentGrades?.addView(
                TableRow(context).apply {
                    addView(createTableCell(nomeDisciplina, false, context))
                    addView(createTableCell(tipoProva, false, context))
                    addView(createTableCell(valor, false, context))
                }
            )
        }
    }

    @SuppressLint("SetTextI18n")
    private fun setupAulasDia(aulas: List<Aula>) {
        if (isFragmentDestroyed) return
        val context = context ?: return

        aulasContainer?.removeAllViews()
        aulasSectionContainer?.visibility = View.VISIBLE

        if (aulas.isEmpty()) {
            aulasContainer?.visibility = View.GONE
            txtSemAulas?.visibility = View.VISIBLE
            return
        }

        aulasContainer?.visibility = View.VISIBLE
        txtSemAulas?.visibility = View.GONE

        for ((_, _, nomeDisciplina, sala, horaInicio, horaFim) in aulas) {
            val card = LayoutInflater.from(context)
                .inflate(R.layout.item_aula_card, aulasContainer, false) as MaterialCardView
            card.findViewById<TextView>(R.id.txtAulaDisciplina).text = nomeDisciplina
            card.findViewById<TextView>(R.id.txtAulaHorario).text =
                "$horaInicio - $horaFim"
            card.findViewById<TextView>(R.id.txtAulaSala).text = sala
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

        viewPager.layoutParams = viewPager.layoutParams.apply {
            height = calculatedHeight.coerceIn(minHeight, maxHeight)
        }
    }

    private fun handleDataFetchError() {
        if (isFragmentDestroyed) return
        if (carouselItems.isEmpty()) showOfflineState()
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
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_carousel, parent, false)
            return CarouselViewHolder(view)
        }

        override fun onBindViewHolder(holder: CarouselViewHolder, position: Int) {
            val item = carouselItems[position]
            holder.imageView.scaleType = ImageView.ScaleType.FIT_XY

            // ★ BUG 13: imageUrl é String? — GlideUrl(null) lançava NPE.
            val imageUrl = item.imageUrl
            if (imageUrl.isNullOrEmpty()) {
                holder.imageView.setImageDrawable(null)
                holder.itemView.setOnClickListener(null)
                return
            }

            // ★ BUG 50: não inicia load com o fragment/activity destruído.
            if (isFragmentDestroyed || !isAdded) return

            val headersBuilder = LazyHeaders.Builder()
                .addHeader("User-Agent", USER_AGENT)
                .addHeader("Referer", HOME_URL)
            carouselCookieHeader?.let { headersBuilder.addHeader("Cookie", it) }

            val glideUrl = GlideUrl(imageUrl, headersBuilder.build())
            val requestOptions = RequestOptions()
                .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
                .timeout(15000)
                .signature(ObjectKey("carousel_v2"))

            Glide.with(holder.itemView)
                .asBitmap()
                .load(glideUrl)
                .apply(requestOptions)
                .listener(object : RequestListener<android.graphics.Bitmap> {
                    override fun onLoadFailed(
                        e: GlideException?, model: Any?,
                        target: Target<android.graphics.Bitmap>, isFirstResource: Boolean
                    ): Boolean {
                        Log.e(TAG, "Falha ao carregar imagem do carrossel")
                        return false
                    }

                    override fun onResourceReady(
                        resource: android.graphics.Bitmap, model: Any?,
                        target: Target<android.graphics.Bitmap>?,
                        dataSource: DataSource, isFirstResource: Boolean
                    ) = false
                })
                .into(holder.imageView)

            holder.itemView.setOnClickListener {
                item.linkUrl?.let { link ->
                    runCatching { startActivity(Intent(Intent.ACTION_VIEW, link.toUri())) }
                        .onFailure { Log.e(TAG, "Erro ao abrir link do carrossel", it) }
                }
            }
        }

        override fun getItemCount() = carouselItems.size
    }

    internal class CarouselViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val imageView: ImageView = itemView.findViewById(R.id.imageView)
    }
}