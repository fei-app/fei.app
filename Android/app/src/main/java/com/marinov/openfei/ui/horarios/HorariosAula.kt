package com.marinov.openfei.ui.horarios

import android.annotation.SuppressLint
import android.graphics.Typeface
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.color.MaterialColors
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.marinov.openfei.data.Aula
import com.marinov.openfei.data.AulasRepository
import com.marinov.openfei.data.SessionExpiredException
import com.marinov.openfei.ui.main.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

class HorariosAula : Fragment(){

    private lateinit var viewPagerAulas: ViewPager2
    private lateinit var barOffline: LinearLayout
    private lateinit var progressBar: CircularProgressIndicator
    private lateinit var tvMessage: TextView
    private lateinit var btnLogin: MaterialButton
    private var chipGroupDias: ChipGroup? = null
    private var scrollChips: HorizontalScrollView? = null
    private var layoutDias: LinearLayout? = null
    private val tabletDayButtons = LinkedHashMap<String, MaterialButton>()
    private var todasAulas: List<Aula> = emptyList()
    private var diasVisiveis: List<String> = emptyList()

    private val ordemDias: List<String> by lazy {
        listOf(
            getString(com.marinov.openfei.R.string.horarios_segunda),
            getString(com.marinov.openfei.R.string.horarios_terca),
            getString(com.marinov.openfei.R.string.horarios_quarta),
            getString(com.marinov.openfei.R.string.horarios_quinta),
            getString(com.marinov.openfei.R.string.horarios_sexta),
            getString(com.marinov.openfei.R.string.horarios_sabado)
        )
    }

    private var diaSelecionado: String = ""
    private var isProgrammaticChipUpdate = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val root = inflater.inflate(com.marinov.openfei.R.layout.fragment_horarios, container, false)

        viewPagerAulas = root.findViewById(com.marinov.openfei.R.id.viewPagerAulas)
        barOffline     = root.findViewById(com.marinov.openfei.R.id.barOffline)
        progressBar    = root.findViewById(com.marinov.openfei.R.id.progressBar)
        tvMessage      = root.findViewById(com.marinov.openfei.R.id.tvMessage)
        btnLogin       = root.findViewById(com.marinov.openfei.R.id.btnLogin)
        chipGroupDias = root.findViewById(com.marinov.openfei.R.id.chipGroupDias)
        scrollChips   = root.findViewById(com.marinov.openfei.R.id.scrollChips)
        layoutDias    = root.findViewById(com.marinov.openfei.R.id.layoutDias)

        btnLogin.setOnClickListener { carregarHorarios() }
        carregarHorarios()

        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    (activity as? MainActivity)?.navigateToHome()
                }
            }
        )
        return root
    }

    override fun onDestroyView() {
        if (::viewPagerAulas.isInitialized) {
            viewPagerAulas.unregisterOnPageChangeCallback(pageChangeCallback)
            viewPagerAulas.adapter = null
        }
        super.onDestroyView()
    }

    private fun getDiaAtual(): String = when (Calendar.getInstance().get(Calendar.DAY_OF_WEEK)) {
        Calendar.MONDAY    -> getString(com.marinov.openfei.R.string.horarios_segunda)
        Calendar.TUESDAY   -> getString(com.marinov.openfei.R.string.horarios_terca)
        Calendar.WEDNESDAY -> getString(com.marinov.openfei.R.string.horarios_quarta)
        Calendar.THURSDAY  -> getString(com.marinov.openfei.R.string.horarios_quinta)
        Calendar.FRIDAY    -> getString(com.marinov.openfei.R.string.horarios_sexta)
        Calendar.SATURDAY  -> getString(com.marinov.openfei.R.string.horarios_sabado)
        else               -> getString(com.marinov.openfei.R.string.horarios_segunda)
    }

    private fun proximoDiaComAula(diasComAula: Set<String>): String {
        if (diasComAula.isEmpty()) return ordemDias.first()
        val indexHoje = ordemDias.indexOf(getDiaAtual()).coerceAtLeast(0)
        for (offset in ordemDias.indices) {
            val candidato = ordemDias[(indexHoje + offset) % ordemDias.size]
            if (candidato in diasComAula) return candidato
        }
        return diasComAula.first()
    }

    private fun construirSeletorDias(diasComAula: Set<String>) {
        diaSelecionado = proximoDiaComAula(diasComAula)
        diasVisiveis   = ordemDias.filter { it in diasComAula }
        chipGroupDias?.let { construirChips(it) }
        layoutDias?.let    { construirBotoesTablet(it) }
    }

    private fun construirChips(chipGroup: ChipGroup) {
        chipGroup.removeAllViews()
        diasVisiveis.forEachIndexed { index, dia ->
            Chip(requireContext(), null, com.google.android.material.R.attr.chipStyle).apply {
                text = dia
                isCheckable = true
                isChecked = (dia == diaSelecionado)
                setOnCheckedChangeListener { _, isChecked ->
                    if (isChecked && !isProgrammaticChipUpdate) {
                        if (::viewPagerAulas.isInitialized) {
                            viewPagerAulas.setCurrentItem(index, true)
                        }
                    }
                }
                chipGroup.addView(this)
            }
        }
    }

    private fun construirBotoesTablet(dayLayout: LinearLayout) {
        dayLayout.removeAllViews()
        tabletDayButtons.clear()

        diasVisiveis.forEach { dia ->
            val btn = MaterialButton(
                requireContext(),
                null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle
            ).apply {
                text = dia
                isAllCaps = false
                textAlignment = View.TEXT_ALIGNMENT_VIEW_START
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 4.dp, 0, 4.dp) }

                setOnClickListener {
                    if (::viewPagerAulas.isInitialized) {
                        viewPagerAulas.setCurrentItem(diasVisiveis.indexOf(dia), true)
                    }
                }
            }
            tabletDayButtons[dia] = btn
            dayLayout.addView(btn)
        }
        atualizarEstiloBotoesDia()
    }

    private fun sincronizarSeletorComDia(dia: String) {
        diaSelecionado = dia
        chipGroupDias?.let { group ->
            val index = diasVisiveis.indexOf(dia).takeIf { it >= 0 } ?: return@let
            isProgrammaticChipUpdate = true
            (group.getChildAt(index) as? Chip)?.let { chip ->
                chip.isChecked = true
                scrollChips?.post {
                    if (isAdded && view != null) {
                        val chipLeft   = chip.left
                        val chipWidth  = chip.width
                        val scrollWidth = scrollChips?.width ?: 0
                        scrollChips?.smoothScrollTo(chipLeft - (scrollWidth - chipWidth) / 2, 0)
                    }
                }
            }
            isProgrammaticChipUpdate = false
        }
        atualizarEstiloBotoesDia()
    }

    private fun atualizarEstiloBotoesDia() {
        if (tabletDayButtons.isEmpty()) return
        val ctx = context ?: return
        val bgSel   = MaterialColors.getColor(ctx, com.google.android.material.R.attr.colorSecondaryContainer, 0)
        val textSel = MaterialColors.getColor(ctx, com.google.android.material.R.attr.colorOnSecondaryContainer, 0)
        val textNorm= MaterialColors.getColor(ctx, com.google.android.material.R.attr.colorOnSurface, 0)

        tabletDayButtons.forEach { (dia, btn) ->
            if (dia == diaSelecionado) {
                btn.setBackgroundColor(bgSel)
                btn.setTextColor(textSel)
                btn.setTypeface(null, Typeface.BOLD)
            } else {
                btn.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                btn.setTextColor(textNorm)
                btn.setTypeface(null, Typeface.NORMAL)
            }
        }
    }

    private fun carregarHorarios() {
        val mainActivity = activity as? MainActivity ?: return

        lifecycleScope.launch {
            exibirCarregando()

            val status = mainActivity.checkConnectionAndSession()
            if (status == MainActivity.STATUS_LOGIN_NEEDED) {
                mainActivity.setRefreshing(false)
                return@launch
            }

            val online = status == MainActivity.STATUS_ONLINE_OK
            barOffline.visibility = if (!online) View.VISIBLE else View.GONE

            try {
                val aulas = withContext(Dispatchers.IO) { AulasRepository.aulas(online) }
                progressBar.visibility = View.GONE

                if (aulas.isEmpty()) {
                    mostrarMensagem(getString(com.marinov.openfei.R.string.horarios_nenhuma_aula))
                } else {
                    todasAulas = aulas
                    val diasComAula = aulas.map { it.diaSemana }.toSet()
                    construirSeletorDias(diasComAula)
                    configurarViewPager()
                }
            } catch (_: SessionExpiredException) {
                Log.w("HorariosAula", "Sessão expirada durante carregamento de horários")
                progressBar.visibility = View.GONE
                withContext(Dispatchers.Main) { mainActivity.checkConnectionAndSession() }
            } catch (e: Exception) {
                Log.e("HorariosAula", "Erro ao carregar horários", e)
                progressBar.visibility = View.GONE
                mostrarMensagem(getString(com.marinov.openfei.R.string.horarios_erro_carregar))
            } finally {
                mainActivity.setRefreshing(false)
            }
        }
    }

    private fun configurarViewPager() {
        if (diasVisiveis.isEmpty()) {
            mostrarMensagem(getString(com.marinov.openfei.R.string.horarios_nenhuma_aula))
            return
        }

        val aulasPorDia = diasVisiveis.associateWith { dia ->
            todasAulas.filter { it.diaSemana == dia }.sortedBy { it.horaInicio }
        }

        viewPagerAulas.orientation = if (layoutDias != null)
            ViewPager2.ORIENTATION_VERTICAL
        else
            ViewPager2.ORIENTATION_HORIZONTAL

        viewPagerAulas.adapter = DiasPageAdapter(diasVisiveis, aulasPorDia)

        val initialIndex = diasVisiveis.indexOf(diaSelecionado).coerceAtLeast(0)
        viewPagerAulas.setCurrentItem(initialIndex, false)

        viewPagerAulas.unregisterOnPageChangeCallback(pageChangeCallback)
        viewPagerAulas.registerOnPageChangeCallback(pageChangeCallback)

        tvMessage.visibility     = View.GONE
        viewPagerAulas.visibility = View.VISIBLE
    }

    private val pageChangeCallback = object : ViewPager2.OnPageChangeCallback() {
        override fun onPageSelected(position: Int) {
            if (position in diasVisiveis.indices) {
                sincronizarSeletorComDia(diasVisiveis[position])
            }
        }
    }

    private fun exibirCarregando() {
        progressBar.visibility    = View.VISIBLE
        viewPagerAulas.visibility = View.GONE
        tvMessage.visibility      = View.GONE
        barOffline.visibility     = View.GONE
    }

    private fun mostrarMensagem(msg: String) {
        tvMessage.text            = msg
        tvMessage.visibility      = View.VISIBLE
        viewPagerAulas.visibility = View.GONE
        progressBar.visibility    = View.GONE
    }

    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()

    private inner class DiasPageAdapter(
        private val dias: List<String>,
        private val aulasPorDia: Map<String, List<Aula>>
    ) : RecyclerView.Adapter<DiasPageAdapter.PageHolder>() {

        override fun getItemCount() = dias.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(com.marinov.openfei.R.layout.item_dia_page, parent, false)
            return PageHolder(view)
        }

        override fun onBindViewHolder(holder: PageHolder, position: Int) {
            val dia = dias[position]
            holder.bind(aulasPorDia[dia] ?: emptyList(), dia)
        }

        inner class PageHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val recycler: RecyclerView = itemView.findViewById(com.marinov.openfei.R.id.recyclerAulasPage)
            private val tvEmpty: TextView      = itemView.findViewById(com.marinov.openfei.R.id.tvEmptyPage)

            init { recycler.layoutManager = LinearLayoutManager(itemView.context) }

            fun bind(aulas: List<Aula>, dia: String) {
                if (aulas.isEmpty()) {
                    recycler.visibility = View.GONE
                    recycler.adapter = null
                    tvEmpty.visibility  = View.VISIBLE
                    tvEmpty.text        = getString(com.marinov.openfei.R.string.horarios_nenhuma_aula_dia, dia)
                } else {
                    tvEmpty.visibility  = View.GONE
                    recycler.visibility = View.VISIBLE
                    recycler.adapter    = AulasAdapter(aulas)
                }
            }
        }
    }

    private inner class AulasAdapter(
        private val items: List<Aula>
    ) : RecyclerView.Adapter<AulasAdapter.ViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(com.marinov.openfei.R.layout.item_aula_horario, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(items[position])

        override fun getItemCount() = items.size

        inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val txtCodigo:  TextView = itemView.findViewById(com.marinov.openfei.R.id.txtCodigoDisciplina)
            private val txtNome:    TextView = itemView.findViewById(com.marinov.openfei.R.id.txtNomeDisciplina)
            private val txtHorario: TextView = itemView.findViewById(com.marinov.openfei.R.id.txtHorario)
            private val txtSala:    TextView = itemView.findViewById(com.marinov.openfei.R.id.txtSala)

            @SuppressLint("SetTextI18n")
            fun bind(aula: Aula) {
                txtCodigo.text  = aula.codigoDisciplina
                txtNome.text    = aula.nomeDisciplina.ifBlank { aula.codigoDisciplina }
                txtHorario.text = "${aula.horaInicio} – ${aula.horaFim}"
                txtSala.text    = aula.sala.ifBlank { getString(com.marinov.openfei.R.string.horarios_sala_nao_informada) }
            }
        }
    }
}