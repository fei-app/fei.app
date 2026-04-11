package com.marinov.openfei

import android.annotation.SuppressLint
import android.graphics.Typeface
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.color.MaterialColors
import com.google.android.material.progressindicator.CircularProgressIndicator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

class HorariosAula : Fragment(), MainActivity.RefreshableFragment {

    private lateinit var recyclerAulas: RecyclerView
    private lateinit var barOffline: LinearLayout
    private lateinit var progressBar: CircularProgressIndicator
    private lateinit var tvMessage: TextView
    private lateinit var btnLogin: MaterialButton
    private lateinit var adapter: AulasAdapter

    private var chipGroupDias: ChipGroup? = null

    private var layoutDias: LinearLayout? = null

    private val tabletDayButtons = LinkedHashMap<String, MaterialButton>()

    private var todasAulas: List<Dados.Aula> = emptyList()

    private val ordemDias = listOf("Segunda", "Terça", "Quarta", "Quinta", "Sexta", "Sábado")

    private var diaSelecionado: String = getDiaAtual()

    // =====================================================================
    // Ciclo de vida
    // =====================================================================

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val root = inflater.inflate(R.layout.fragment_horarios, container, false)

        recyclerAulas = root.findViewById(R.id.recyclerAulas)
        barOffline    = root.findViewById(R.id.barOffline)
        progressBar   = root.findViewById(R.id.progressBar)
        tvMessage     = root.findViewById(R.id.tvMessage)
        btnLogin      = root.findViewById(R.id.btnLogin)

        // Um dos dois será nulo dependendo do layout carregado (celular × tablet)
        chipGroupDias = root.findViewById(R.id.chipGroupDias)
        layoutDias    = root.findViewById(R.id.layoutDias)

        btnLogin.setOnClickListener { carregarHorarios() }

        setupRecyclerView()
        carregarHorarios()

        return root
    }

    override fun onRefresh() {
        Log.d("HorariosAula", "Pull-to-Refresh acionado")
        carregarHorarios()
    }

    // =====================================================================
    // Lógica de dia
    // =====================================================================

    private fun getDiaAtual(): String = when (Calendar.getInstance().get(Calendar.DAY_OF_WEEK)) {
        Calendar.MONDAY    -> "Segunda"
        Calendar.TUESDAY   -> "Terça"
        Calendar.WEDNESDAY -> "Quarta"
        Calendar.THURSDAY  -> "Quinta"
        Calendar.FRIDAY    -> "Sexta"
        Calendar.SATURDAY  -> "Sábado"
        else               -> "Segunda"
    }

    private fun proximoDiaComAula(diasComAula: Set<String>): String {
        if (diasComAula.isEmpty()) return ordemDias.first()

        val indexHoje = ordemDias.indexOf(getDiaAtual()).takeIf { it >= 0 } ?: 0

        for (offset in ordemDias.indices) {
            val candidato = ordemDias[(indexHoje + offset) % ordemDias.size]
            if (candidato in diasComAula) return candidato
        }
        return diasComAula.first()
    }

    // =====================================================================
    // Seletor de dias (chips / botões)
    // =====================================================================
    private fun construirSeletorDias(diasComAula: Set<String>) {
        diaSelecionado = proximoDiaComAula(diasComAula)

        val chipGroup = chipGroupDias
        val dayLayout = layoutDias

        if (chipGroup != null) {
            construirChips(chipGroup, diasComAula)
        } else if (dayLayout != null) {
            construirBotoesTablet(dayLayout, diasComAula)
        }
    }

    private fun construirChips(chipGroup: ChipGroup, diasComAula: Set<String>) {
        chipGroup.removeAllViews()

        val diasVisiveis = ordemDias.filter { it in diasComAula }
        val chips = diasVisiveis.map { dia ->
            Chip(requireContext(), null, com.google.android.material.R.attr.chipStyle).apply {
                text = dia
                isCheckable = true
                chipGroup.addView(this)
            }
        }
        chips.forEachIndexed { index, chip ->
            chip.isChecked = diasVisiveis[index] == diaSelecionado
            chip.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) selecionarDia(diasVisiveis[index])
            }
        }
    }

    private fun construirBotoesTablet(dayLayout: LinearLayout, diasComAula: Set<String>) {
        dayLayout.removeAllViews()
        tabletDayButtons.clear()

        val diasVisiveis = ordemDias.filter { it in diasComAula }
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
                setOnClickListener { selecionarDia(dia) }
            }
            tabletDayButtons[dia] = btn
            dayLayout.addView(btn)
        }

        atualizarEstiloBotoesDia()
    }

    private fun selecionarDia(dia: String) {
        diaSelecionado = dia
        atualizarEstiloBotoesDia()
        aplicarFiltroDia()
    }
    private fun atualizarEstiloBotoesDia() {
        if (tabletDayButtons.isEmpty()) return
        val ctx = context ?: return

        val bgSelecionado = MaterialColors.getColor(
            ctx,
            com.google.android.material.R.attr.colorSecondaryContainer,
            0
        )
        val textSelecionado = MaterialColors.getColor(
            ctx,
            com.google.android.material.R.attr.colorOnSecondaryContainer,
            0
        )
        val textNormal = MaterialColors.getColor(
            ctx,
            com.google.android.material.R.attr.colorOnSurface,
            0
        )

        tabletDayButtons.forEach { (dia, btn) ->
            if (dia == diaSelecionado) {
                btn.setBackgroundColor(bgSelecionado)
                btn.setTextColor(textSelecionado)
                btn.setTypeface(null, Typeface.BOLD)
            } else {
                btn.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                btn.setTextColor(textNormal)
                btn.setTypeface(null, Typeface.NORMAL)
            }
        }
    }

    // =====================================================================
    // Carregamento de dados
    // =====================================================================

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
                val aulas = withContext(Dispatchers.IO) { Dados.aulas(online) }
                progressBar.visibility = View.GONE

                if (aulas.isEmpty()) {
                    mostrarMensagem("Nenhuma aula encontrada")
                } else {
                    todasAulas = aulas

                    // Descobre quais dias têm pelo menos uma aula e constrói o seletor
                    val diasComAula = aulas.map { it.diaSemana }.toSet()
                    construirSeletorDias(diasComAula)

                    // Exibe as aulas do dia pré-selecionado
                    aplicarFiltroDia()
                }

            } catch (e: SessionExpiredException) {
                Log.w("HorariosAula", "Sessão expirada durante carregamento de horários")
                progressBar.visibility = View.GONE
                withContext(Dispatchers.Main) { mainActivity.checkConnectionAndSession() }

            } catch (e: Exception) {
                Log.e("HorariosAula", "Erro ao carregar horários", e)
                progressBar.visibility = View.GONE
                mostrarMensagem("Erro ao carregar horários")

            } finally {
                mainActivity.setRefreshing(false)
            }
        }
    }

    // =====================================================================
    // Filtragem e exibição
    // =====================================================================

    private fun aplicarFiltroDia() {
        val aulasDoDia = todasAulas
            .filter { it.diaSemana == diaSelecionado }
            .sortedBy { it.horaInicio }

        adapter.updateData(aulasDoDia)

        if (aulasDoDia.isEmpty()) {
            mostrarMensagem("Nenhuma aula em $diaSelecionado")
        } else {
            tvMessage.visibility = View.GONE
            recyclerAulas.visibility = View.VISIBLE
        }
    }

    // =====================================================================
    // RecyclerView
    // =====================================================================

    private fun setupRecyclerView() {
        adapter = AulasAdapter(emptyList())
        recyclerAulas.layoutManager = LinearLayoutManager(requireContext())
        recyclerAulas.adapter = adapter
    }

    // =====================================================================
    // Helpers de estado visual
    // =====================================================================

    private fun exibirCarregando() {
        progressBar.visibility = View.VISIBLE
        recyclerAulas.visibility = View.GONE
        tvMessage.visibility = View.GONE
        barOffline.visibility = View.GONE
    }

    private fun mostrarMensagem(msg: String) {
        tvMessage.text = msg
        tvMessage.visibility = View.VISIBLE
        recyclerAulas.visibility = View.GONE
        progressBar.visibility = View.GONE
    }

    // =====================================================================
    // Extensão utilitária
    // =====================================================================

    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()

    // =====================================================================
    // Adapter
    // =====================================================================

    private inner class AulasAdapter(
        private var items: List<Dados.Aula>
    ) : RecyclerView.Adapter<AulasAdapter.ViewHolder>() {

        @SuppressLint("NotifyDataSetChanged")
        fun updateData(newItems: List<Dados.Aula>) {
            items = newItems
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_aula_horario, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount(): Int = items.size

        inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val txtCodigo: TextView  = itemView.findViewById(R.id.txtCodigoDisciplina)
            private val txtNome: TextView    = itemView.findViewById(R.id.txtNomeDisciplina)
            private val txtHorario: TextView = itemView.findViewById(R.id.txtHorario)
            private val txtSala: TextView    = itemView.findViewById(R.id.txtSala)

            fun bind(aula: Dados.Aula) {
                txtCodigo.text  = aula.codigoDisciplina
                txtNome.text    = aula.nomeDisciplina.ifBlank { aula.codigoDisciplina }
                txtHorario.text = "${aula.horaInicio} – ${aula.horaFim}"
                txtSala.text    = aula.sala.ifBlank { "Sala não informada" }
            }
        }
    }
}