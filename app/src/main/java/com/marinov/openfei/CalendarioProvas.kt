package com.marinov.openfei

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.ImageButton
import android.widget.PopupMenu
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.CircularProgressIndicator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

class CalendarioProvas : Fragment() {

    private companion object {
        const val FILTRO_TODOS = 0
        const val FILTRO_P1 = 1
        const val FILTRO_P2 = 2
        const val FILTRO_P3 = 3
    }

    private lateinit var recyclerProvas: RecyclerView
    private lateinit var progressBar: CircularProgressIndicator
    private lateinit var barOffline: View
    private lateinit var txtSemProvas: TextView
    private lateinit var txtSemDados: TextView
    private lateinit var btnLogin: MaterialButton
    private lateinit var spinnerMes: Spinner
    private lateinit var btnFiltro: ImageButton
    private lateinit var adapter: ProvasCalendarioAdapter

    private var todasProvas: List<Dados.ProvaCalendario> = emptyList()
    private var mesSelecionado: Int = 1  // 1 a 12 (Janeiro a Dezembro)
    private var filtroAtual: Int = FILTRO_TODOS

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_provas_calendar, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        if (!isAdded) return

        recyclerProvas = view.findViewById(R.id.recyclerProvas)
        progressBar = view.findViewById(R.id.progress_circular)
        barOffline = view.findViewById(R.id.barOffline)
        txtSemProvas = view.findViewById(R.id.txt_sem_provas)
        txtSemDados = view.findViewById(R.id.txt_sem_dados)
        spinnerMes = view.findViewById(R.id.spinner_mes)
        btnLogin = view.findViewById(R.id.btnLogin)
        btnFiltro = view.findViewById(R.id.btnFiltro)

        configurarSpinnerMeses()
        setupRecyclerView()

        btnLogin.setOnClickListener {
            (activity as? MainActivity)?.navigateToHome()
        }

        btnFiltro.setOnClickListener {
            mostrarMenuFiltro(it)
        }

        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    (activity as? MainActivity)?.navigateToHome()
                }
            }
        )

        carregarDados()
    }

    private fun configurarSpinnerMeses() {
        if (!isAdded) return
        val adapter = ArrayAdapter.createFromResource(
            requireContext(),
            R.array.meses_array,
            android.R.layout.simple_spinner_item
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

        spinnerMes.adapter = adapter

        // Seleciona o mês atual por padrão (índice = mês - 1)
        val calendar = Calendar.getInstance()
        val mesAtual = calendar.get(Calendar.MONTH) + 1 // Calendar.MONTH é 0-based
        mesSelecionado = mesAtual
        spinnerMes.setSelection(mesAtual - 1)

        spinnerMes.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                // position 0 = Janeiro (mês 1)
                mesSelecionado = position + 1
                aplicarFiltros()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun setupRecyclerView() {
        if (!isAdded) return
        recyclerProvas.layoutManager = LinearLayoutManager(requireContext())
        adapter = ProvasCalendarioAdapter(emptyList()) { prova ->
            abrirDetalhesProva(prova)
        }
        recyclerProvas.adapter = adapter
    }

    private fun abrirDetalhesProva(prova: Dados.ProvaCalendario) {
        val args = Bundle().apply {
            putString("codigo", prova.disciplina)
            putString("nome", prova.nomeDisciplina)
            putString("data", prova.dataProva)
            putString("tipo", prova.tipoProva)
        }
        val fragment = MateriadeProva().apply { arguments = args }

        val transaction = parentFragmentManager.beginTransaction()
        if (resources.getBoolean(R.bool.isTablet)) {
            val currentDetail = parentFragmentManager.findFragmentById(R.id.detail_container)
            if (currentDetail != null) {
                transaction.remove(currentDetail)
            }
            transaction.replace(R.id.detail_container, fragment)
        } else {
            transaction.replace(R.id.nav_host_fragment, fragment)
            transaction.addToBackStack(null)
        }
        transaction.commit()
    }

    private fun mostrarMenuFiltro(anchor: View) {
        if (!isAdded) return
        val popup = PopupMenu(requireContext(), anchor)
        popup.menuInflater.inflate(R.menu.menu_filtro_provas_calendario, popup.menu)

        when (filtroAtual) {
            FILTRO_TODOS -> popup.menu.findItem(R.id.filtro_todos).isChecked = true
            FILTRO_P1 -> popup.menu.findItem(R.id.filtro_p1).isChecked = true
            FILTRO_P2 -> popup.menu.findItem(R.id.filtro_p2).isChecked = true
            FILTRO_P3 -> popup.menu.findItem(R.id.filtro_p3).isChecked = true
        }

        popup.setOnMenuItemClickListener { item ->
            filtroAtual = when (item.itemId) {
                R.id.filtro_todos -> FILTRO_TODOS
                R.id.filtro_p1 -> FILTRO_P1
                R.id.filtro_p2 -> FILTRO_P2
                R.id.filtro_p3 -> FILTRO_P3
                else -> return@setOnMenuItemClickListener false
            }
            aplicarFiltros()
            true
        }

        popup.show()
    }

    private fun carregarDados() {
        lifecycleScope.launch {
            val mainActivity = activity as? MainActivity ?: return@launch
            val status = mainActivity.checkConnectionAndSession()

            when (status) {
                MainActivity.STATUS_LOGIN_NEEDED -> {
                    exibirBarraOffline()
                    exibirSemDados()
                }
                MainActivity.STATUS_OFFLINE -> {
                    exibirBarraOffline()
                    carregarProvas(online = false)
                }
                MainActivity.STATUS_ONLINE_OK -> {
                    esconderBarraOffline()
                    carregarProvas(online = true)
                }
            }
        }
    }

    private suspend fun carregarProvas(online: Boolean) {
        exibirCarregando()

        try {
            val provas = withContext(Dispatchers.IO) {
                Dados.obterCalendarioProvas(online)
            }

            if (!isAdded) return

            progressBar.visibility = View.GONE
            todasProvas = provas

            if (provas.isEmpty()) {
                exibirMensagemSemProvas()
            } else {
                exibirConteudo()
                aplicarFiltros()
            }
        } catch (e: SessionExpiredException) {
            exibirBarraOffline()
            exibirSemDados()
        } catch (e: Exception) {
            exibirBarraOffline()
            exibirSemDados()
        }
    }

    private fun aplicarFiltros() {
        if (!::adapter.isInitialized) return

        val listaFiltrada = todasProvas.filter { prova ->
            // Filtro por tipo
            val passaTipo = when (filtroAtual) {
                FILTRO_P1 -> prova.tipoProva == "P1"
                FILTRO_P2 -> prova.tipoProva == "P2"
                FILTRO_P3 -> prova.tipoProva == "P3"
                else -> true
            }

            // Filtro por mês (obrigatório, pois não há opção "Todos")
            val partes = prova.dataProva.split("/")
            val passaMes = if (partes.size == 2) {
                val mes = partes[1].toIntOrNull() ?: 0
                mes == mesSelecionado
            } else {
                false
            }

            passaTipo && passaMes
        }

        adapter.updateData(listaFiltrada)

        if (listaFiltrada.isEmpty()) {
            txtSemProvas.visibility = View.VISIBLE
            recyclerProvas.visibility = View.GONE
        } else {
            txtSemProvas.visibility = View.GONE
            recyclerProvas.visibility = View.VISIBLE
        }
    }

    private fun exibirCarregando() {
        barOffline.visibility = View.GONE
        progressBar.visibility = View.VISIBLE
        recyclerProvas.visibility = View.GONE
        txtSemProvas.visibility = View.GONE
        txtSemDados.visibility = View.GONE
    }

    private fun exibirConteudo() {
        recyclerProvas.visibility = View.VISIBLE
        txtSemProvas.visibility = View.GONE
        txtSemDados.visibility = View.GONE
    }

    private fun exibirMensagemSemProvas() {
        recyclerProvas.visibility = View.GONE
        txtSemProvas.visibility = View.VISIBLE
        txtSemDados.visibility = View.GONE
    }

    private fun exibirSemDados() {
        recyclerProvas.visibility = View.GONE
        txtSemProvas.visibility = View.GONE
        txtSemDados.visibility = View.VISIBLE
    }

    private fun exibirBarraOffline() {
        barOffline.visibility = View.VISIBLE
    }

    private fun esconderBarraOffline() {
        barOffline.visibility = View.GONE
    }

    // ===================== ADAPTER =====================
    private inner class ProvasCalendarioAdapter(
        private var items: List<Dados.ProvaCalendario>,
        private val onItemClick: (Dados.ProvaCalendario) -> Unit
    ) : RecyclerView.Adapter<ProvasCalendarioAdapter.ViewHolder>() {

        @SuppressLint("NotifyDataSetChanged")
        fun updateData(newItems: List<Dados.ProvaCalendario>) {
            items = newItems
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_prova_calendario, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.bind(item)
            holder.itemView.setOnClickListener { onItemClick(item) }
        }

        override fun getItemCount() = items.size

        inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val txtDisciplina: TextView = itemView.findViewById(R.id.txt_disciplina)
            private val txtDataHora: TextView = itemView.findViewById(R.id.txt_data_hora)
            private val txtSala: TextView = itemView.findViewById(R.id.txt_sala)
            private val txtCoordenador: TextView = itemView.findViewById(R.id.txt_coordenador)
            private val txtTipoProva: TextView = itemView.findViewById(R.id.txt_tipo_prova)

            fun bind(prova: Dados.ProvaCalendario) {
                txtDisciplina.text = "${prova.disciplina} - ${prova.nomeDisciplina}"
                txtDataHora.text = "${prova.dataProva} - ${prova.hora}"
                txtSala.text = prova.sala
                txtCoordenador.text = prova.coordenador
                txtTipoProva.text = prova.tipoProva

                when (prova.tipoProva) {
                    "P3" -> {
                        txtTipoProva.setBackgroundResource(R.drawable.bg_amarelo)
                        txtTipoProva.setTextColor(android.graphics.Color.BLACK)
                    }
                    else -> {
                        txtTipoProva.setBackgroundResource(R.drawable.bg_azul)
                        txtTipoProva.setTextColor(android.graphics.Color.WHITE)
                    }
                }
            }
        }
    }
}