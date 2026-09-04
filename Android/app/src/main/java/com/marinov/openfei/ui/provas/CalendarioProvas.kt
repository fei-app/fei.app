package com.marinov.openfei.ui.provas

import android.annotation.SuppressLint
import android.os.Bundle
import android.util.Log
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
import com.marinov.openfei.data.CalendarioRepository
import com.marinov.openfei.data.CalendarSyncManager
import com.marinov.openfei.data.ProvaCalendario
import com.marinov.openfei.data.SessionExpiredException
import com.marinov.openfei.ui.main.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

class CalendarioProvas : Fragment() {

    private companion object {
        const val TAG = "CalendarioProvas"

        const val FILTRO_TODOS = 0
        const val FILTRO_P1 = 1
        const val FILTRO_P2 = 2
        const val FILTRO_P3 = 3
        const val FILTRO_MOODLE = 4
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

    private var todasProvasFEI: List<ProvaCalendario> = emptyList()
    private var todosEventosMoodle: List<ProvaCalendario> = emptyList()
    private var mesSelecionado: Int = 1
    private var filtroAtual: Int = FILTRO_TODOS
    private var dadosCarregados: Boolean = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(
            com.marinov.openfei.R.layout.fragment_provas_calendar,
            container,
            false
        )
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        if (!isAdded) return

        recyclerProvas = view.findViewById(com.marinov.openfei.R.id.recyclerProvas)
        progressBar = view.findViewById(com.marinov.openfei.R.id.progress_circular)
        barOffline = view.findViewById(com.marinov.openfei.R.id.barOffline)
        txtSemProvas = view.findViewById(com.marinov.openfei.R.id.txt_sem_provas)
        txtSemDados = view.findViewById(com.marinov.openfei.R.id.txt_sem_dados)
        spinnerMes = view.findViewById(com.marinov.openfei.R.id.spinner_mes)
        btnLogin = view.findViewById(com.marinov.openfei.R.id.btnLogin)
        btnFiltro = view.findViewById(com.marinov.openfei.R.id.btnFiltro)

        configurarSpinnerMeses()
        setupRecyclerView()

        btnLogin.setOnClickListener {
            carregarDados()
        }

        btnFiltro.setOnClickListener {
            mostrarMenuFiltro(it)
        }

        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
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
            com.marinov.openfei.R.array.meses_array,
            android.R.layout.simple_spinner_item
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

        spinnerMes.adapter = adapter

        val calendar = Calendar.getInstance()
        val mesAtual = calendar.get(Calendar.MONTH) + 1
        mesSelecionado = mesAtual
        spinnerMes.setSelection(mesAtual - 1)

        spinnerMes.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) {
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

    private fun abrirDetalhesProva(prova: ProvaCalendario) {
        val args = Bundle().apply {
            putString("codigo", prova.disciplina)
            putString("nome", prova.nomeDisciplina)
            putString("data", prova.dataProva)
            putString("tipo", prova.tipoProva)
        }

        val fragment = MateriadeProva().apply {
            arguments = args
        }

        val transaction = parentFragmentManager.beginTransaction()

        if (resources.getBoolean(com.marinov.openfei.R.bool.isTablet)) {
            val currentDetail = parentFragmentManager.findFragmentById(
                com.marinov.openfei.R.id.detail_container
            )

            if (currentDetail != null) {
                transaction.remove(currentDetail)
            }

            transaction.replace(
                com.marinov.openfei.R.id.detail_container,
                fragment
            )
        } else {
            transaction.replace(
                com.marinov.openfei.R.id.nav_host_fragment,
                fragment
            )
            transaction.addToBackStack(null)
        }

        transaction.commit()
    }

    private fun mostrarMenuFiltro(anchor: View) {
        if (!isAdded) return

        val popup = PopupMenu(requireContext(), anchor)
        popup.menuInflater.inflate(
            com.marinov.openfei.R.menu.menu_filtro_provas_calendario,
            popup.menu
        )

        when (filtroAtual) {
            FILTRO_TODOS -> popup.menu.findItem(com.marinov.openfei.R.id.filtro_todos).isChecked = true
            FILTRO_P1 -> popup.menu.findItem(com.marinov.openfei.R.id.filtro_p1).isChecked = true
            FILTRO_P2 -> popup.menu.findItem(com.marinov.openfei.R.id.filtro_p2).isChecked = true
            FILTRO_P3 -> popup.menu.findItem(com.marinov.openfei.R.id.filtro_p3).isChecked = true
            FILTRO_MOODLE -> popup.menu.findItem(com.marinov.openfei.R.id.filtro_moodle).isChecked = true
        }

        popup.setOnMenuItemClickListener { item ->
            filtroAtual = when (item.itemId) {
                com.marinov.openfei.R.id.filtro_todos -> FILTRO_TODOS
                com.marinov.openfei.R.id.filtro_p1 -> FILTRO_P1
                com.marinov.openfei.R.id.filtro_p2 -> FILTRO_P2
                com.marinov.openfei.R.id.filtro_p3 -> FILTRO_P3
                com.marinov.openfei.R.id.filtro_moodle -> FILTRO_MOODLE
                else -> return@setOnMenuItemClickListener false
            }

            aplicarFiltros()
            true
        }

        popup.show()
    }

    private fun carregarDados() {
        lifecycleScope.launch {
            // 1. Prioriza cache local para montar a UI imediatamente
            val cached = withContext(Dispatchers.IO) {
                Pair(
                    CalendarioRepository.obterProvasFEICache(),
                    CalendarioRepository.obterEventosMoodleCache()
                )
            }

            if (!isAdded) return@launch

            Log.d(
                TAG,
                "carregarDados cache inicial | FEI=${cached.first.size} | Moodle=${cached.second.size}"
            )

            todasProvasFEI = cached.first
            todosEventosMoodle = cached.second
            dadosCarregados = true

            val temCache = todasProvasFEI.isNotEmpty() || todosEventosMoodle.isNotEmpty()

            if (temCache) {
                esconderBarraOffline()
                exibirConteudo()
                aplicarFiltros()
            } else {
                exibirCarregando()
            }

            // Se a integração com a agenda estiver ativada, sincroniza o cache imediatamente
            sincronizarAgendaSeNecessario(fetchOnline = false, force = false)

            val mainActivity = activity as? MainActivity ?: return@launch
            val status = mainActivity.checkConnectionAndSession()

            if (!isAdded) return@launch

            Log.d(TAG, "carregarDados status=$status")

            when (status) {
                MainActivity.STATUS_ONLINE_OK -> {
                    esconderBarraOffline()
                    atualizarProvasOnline()
                }

                MainActivity.STATUS_OFFLINE -> {
                    exibirBarraOffline()
                    if (!temCache) exibirSemDados()
                }

                MainActivity.STATUS_LOGIN_NEEDED -> {
                    exibirBarraOffline()
                    if (!temCache) exibirSemDados()
                }
            }
        }
    }

    private suspend fun atualizarProvasOnline() {
        Log.d(TAG, "atualizarProvasOnline iniciado")

        try {
            val provasFEI = try {
                val lista = CalendarioRepository.obterProvasFEI(true)
                Log.d(TAG, "atualizarProvasOnline provas FEI size=${lista.size}")
                lista
            } catch (e: SessionExpiredException) {
                Log.w(TAG, "atualizarProvasOnline sessão expirada em provas FEI, usando cache", e)
                CalendarioRepository.obterProvasFEICache()
            } catch (e: Exception) {
                Log.e(TAG, "atualizarProvasOnline erro em provas FEI, usando cache", e)
                CalendarioRepository.obterProvasFEICache()
            }

            val eventosMoodle = try {
                val lista = CalendarioRepository.obterEventosMoodle(true)
                Log.d(TAG, "atualizarProvasOnline eventos Moodle size=${lista.size}")
                lista
            } catch (e: SessionExpiredException) {
                Log.w(TAG, "atualizarProvasOnline sessão expirada em eventos Moodle, usando cache", e)
                CalendarioRepository.obterEventosMoodleCache()
            } catch (e: Exception) {
                Log.e(TAG, "atualizarProvasOnline erro em eventos Moodle, usando cache", e)
                CalendarioRepository.obterEventosMoodleCache()
            }

            if (!isAdded) return

            val mudou = provasFEI != todasProvasFEI || eventosMoodle != todosEventosMoodle

            Log.d(
                TAG,
                "atualizarProvasOnline resultado | FEI=${provasFEI.size} | Moodle=${eventosMoodle.size} | mudou=$mudou"
            )

            todasProvasFEI = provasFEI
            todosEventosMoodle = eventosMoodle
            dadosCarregados = true

            val combined = provasFEI + eventosMoodle

            if (combined.isEmpty()) {
                exibirMensagemSemProvas()
            } else {
                exibirConteudo()
                aplicarFiltros()
            }

            // Sempre que sincronizar eventos, também tenta refletir na agenda do dispositivo
            sincronizarAgendaSeNecessario(fetchOnline = false, force = mudou)

        } catch (e: Exception) {
            Log.e(TAG, "atualizarProvasOnline erro geral", e)

            if (!isAdded) return

            exibirBarraOffline()

            if (todasProvasFEI.isEmpty() && todosEventosMoodle.isEmpty()) {
                exibirSemDados()
            }
        }
    }

    private fun sincronizarAgendaSeNecessario(fetchOnline: Boolean, force: Boolean) {
        val ctx = context ?: return

        Log.d(
            TAG,
            "sincronizarAgendaSeNecessario | fetchOnline=$fetchOnline | force=$force"
        )

        lifecycleScope.launch(Dispatchers.IO) {
            CalendarSyncManager.syncIfNeeded(
                context = ctx,
                fetchOnline = fetchOnline,
                force = force
            )
        }
    }

    private fun aplicarFiltros() {
        if (!::adapter.isInitialized) return
        if (!dadosCarregados) return

        val listaCombinada = todasProvasFEI + todosEventosMoodle

        val listaFiltrada = listaCombinada.filter { prova ->
            val passaTipo = when (filtroAtual) {
                FILTRO_P1 -> prova.tipoProva == "P1"
                FILTRO_P2 -> prova.tipoProva == "P2"
                FILTRO_P3 -> prova.tipoProva == "P3"
                FILTRO_MOODLE -> prova.tipoProva == "Moodle"
                else -> true
            }

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
        progressBar.visibility = View.VISIBLE
        recyclerProvas.visibility = View.GONE
        txtSemProvas.visibility = View.GONE
        txtSemDados.visibility = View.GONE
    }

    private fun exibirConteudo() {
        progressBar.visibility = View.GONE
        recyclerProvas.visibility = View.VISIBLE
        txtSemProvas.visibility = View.GONE
        txtSemDados.visibility = View.GONE
    }

    private fun exibirMensagemSemProvas() {
        progressBar.visibility = View.GONE
        recyclerProvas.visibility = View.GONE
        txtSemProvas.visibility = View.VISIBLE
        txtSemDados.visibility = View.GONE
    }

    private fun exibirSemDados() {
        progressBar.visibility = View.GONE
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

    private inner class ProvasCalendarioAdapter(
        private var items: List<ProvaCalendario>,
        private val onItemClick: (ProvaCalendario) -> Unit
    ) : RecyclerView.Adapter<ProvasCalendarioAdapter.ViewHolder>() {

        @SuppressLint("NotifyDataSetChanged")
        fun updateData(newItems: List<ProvaCalendario>) {
            items = newItems
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(
                    com.marinov.openfei.R.layout.item_prova_calendario,
                    parent,
                    false
                )

            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.bind(item)
            holder.itemView.setOnClickListener { onItemClick(item) }
        }

        override fun getItemCount() = items.size

        inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {

            private val txtDisciplina: TextView =
                itemView.findViewById(com.marinov.openfei.R.id.txt_disciplina)
            private val txtDataHora: TextView =
                itemView.findViewById(com.marinov.openfei.R.id.txt_data_hora)
            private val txtSala: TextView =
                itemView.findViewById(com.marinov.openfei.R.id.txt_sala)
            private val txtCoordenador: TextView =
                itemView.findViewById(com.marinov.openfei.R.id.txt_coordenador)
            private val txtTipoProva: TextView =
                itemView.findViewById(com.marinov.openfei.R.id.txt_tipo_prova)

            fun bind(prova: ProvaCalendario) {
                if (prova.tipoProva == "Moodle") {
                    txtDisciplina.text = prova.nomeDisciplina
                    txtCoordenador.visibility = View.GONE

                    txtTipoProva.setBackgroundResource(com.marinov.openfei.R.drawable.bg_laranja)
                    txtTipoProva.setTextColor(android.graphics.Color.WHITE)
                } else {
                    txtDisciplina.text = "${prova.disciplina} - ${prova.nomeDisciplina}"
                    txtCoordenador.visibility = View.VISIBLE
                    txtCoordenador.text = prova.coordenador

                    when (prova.tipoProva) {
                        "P3" -> {
                            txtTipoProva.setBackgroundResource(com.marinov.openfei.R.drawable.bg_amarelo)
                            txtTipoProva.setTextColor(android.graphics.Color.BLACK)
                        }

                        else -> {
                            txtTipoProva.setBackgroundResource(com.marinov.openfei.R.drawable.bg_azul)
                            txtTipoProva.setTextColor(android.graphics.Color.WHITE)
                        }
                    }
                }

                txtDataHora.text = "${prova.dataProva} - ${prova.hora}"
                txtSala.text = prova.sala
                txtTipoProva.text = prova.tipoProva
            }
        }
    }
}