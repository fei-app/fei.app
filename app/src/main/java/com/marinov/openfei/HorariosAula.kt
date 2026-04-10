package com.marinov.openfei

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HorariosAula : Fragment(), MainActivity.RefreshableFragment {

    private lateinit var tableHorarios: TableLayout
    private lateinit var barOffline: LinearLayout
    private lateinit var messageContainer: LinearLayout
    private lateinit var tvMessage: TextView
    private lateinit var scrollContainer: androidx.core.widget.NestedScrollView

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val root = inflater.inflate(R.layout.fragment_horarios, container, false)
        tableHorarios = root.findViewById(R.id.tableHorarios)
        barOffline = root.findViewById(R.id.barOffline)
        messageContainer = root.findViewById(R.id.messageContainer)
        tvMessage = root.findViewById(R.id.tvMessage)
        scrollContainer = root.findViewById(R.id.scrollContainer)

        val btnLogin: MaterialButton = root.findViewById(R.id.btnLogin)
        btnLogin.setOnClickListener {
            carregarHorarios()
        }

        carregarHorarios()
        return root
    }

    override fun onRefresh() {
        Log.d("HorariosAula", "Pull-to-Refresh acionado")
        carregarHorarios()
    }

    /**
     * Carrega os horários de acordo com o estado de conexão/sessão reportado pela MainActivity.
     *
     * Caso A (STATUS_LOGIN_NEEDED): LoginActivity já lançada → não faz nada.
     * Caso B (STATUS_ONLINE_OK):    online + logado → busca do servidor, oculta barra offline.
     * Caso C (STATUS_OFFLINE):      sem internet   → exibe barra offline + conteúdo local.
     */
    private fun carregarHorarios() {
        val mainActivity = activity as? MainActivity ?: return

        lifecycleScope.launch {
            // checkConnectionAndSession é suspend: faz requisição real ao servidor
            val status = mainActivity.checkConnectionAndSession()

            if (status == MainActivity.STATUS_LOGIN_NEEDED) {
                // Caso A: LoginActivity já foi lançada; nada a fazer.
                mainActivity.setRefreshing(false)
                return@launch
            }

            val online = status == MainActivity.STATUS_ONLINE_OK

            try {
                val todasAulas = withContext(Dispatchers.IO) { Dados.aulas(online) }

                if (todasAulas.isEmpty()) {
                    mostrarMensagem("Nenhuma aula encontrada")
                    return@launch
                }

                // Caso C: mostra barra offline mas continua exibindo conteúdo local.
                barOffline.visibility = if (!online) View.VISIBLE else View.GONE

                construirTabelaGrade(todasAulas)
                scrollContainer.visibility = View.VISIBLE
                messageContainer.visibility = View.GONE

            } catch (e: SessionExpiredException) {
                // Sessão expirou durante o fetch → delega à MainActivity.
                Log.w("HorariosAula", "Sessão expirada durante carregamento de horários")
                withContext(Dispatchers.Main) { mainActivity.checkConnectionAndSession() }
            } catch (e: Exception) {
                Log.e("HorariosAula", "Erro ao carregar horários", e)
                mostrarMensagem("Erro ao carregar horários")
                if (!online) barOffline.visibility = View.VISIBLE
            } finally {
                mainActivity.setRefreshing(false)
            }
        }
    }

    private fun construirTabelaGrade(aulas: List<Dados.Aula>) {
        tableHorarios.removeAllViews()
        val context = requireContext()
        val headerBgColor = ContextCompat.getColor(context, R.color.colorPrimary)
        val textColor = ContextCompat.getColor(context, R.color.colorOnSurface)

        val diasSemana = listOf("Segunda", "Terça", "Quarta", "Quinta", "Sexta")

        val gradePorDia: Map<String, Map<String, Dados.Aula>> = diasSemana.associateWith { dia ->
            aulas.filter { it.diaSemana == dia }.associateBy { it.horaInicio }
        }
        val aulasSabado = aulas.filter { it.diaSemana == "Sábado" }
        val gradeSabado = aulasSabado.associateBy { it.horaInicio }

        val horariosUnicos = (aulas.map { it.horaInicio } + aulasSabado.map { it.horaInicio }).distinct().sorted()

        val headerRow = TableRow(context).apply { setBackgroundColor(headerBgColor) }
        val onPrimaryColor = ContextCompat.getColor(context, R.color.colorOnPrimary)

        createCell("Horário\n(Seg-Sex)", isHeader = true).also { it.setTextColor(onPrimaryColor); headerRow.addView(it) }
        for (dia in diasSemana) {
            createCell(dia, isHeader = true).also { it.setTextColor(onPrimaryColor); headerRow.addView(it) }
        }
        createCell("Horário\n(Sábado)", isHeader = true).also { it.setTextColor(onPrimaryColor); headerRow.addView(it) }
        createCell("Sábado", isHeader = true).also { it.setTextColor(onPrimaryColor); headerRow.addView(it) }
        tableHorarios.addView(headerRow)

        for (horaInicio in horariosUnicos) {
            val row = TableRow(context)
            row.setBackgroundColor(ContextCompat.getColor(context, android.R.color.transparent))

            createCell(horaInicio, isHeader = false).also {
                it.setTextColor(textColor)
                it.setTypeface(null, Typeface.BOLD)
                row.addView(it)
            }

            for (dia in diasSemana) {
                val aula = gradePorDia[dia]?.get(horaInicio)
                createCell(aula?.codigoDisciplina ?: "", isHeader = false).also { cell ->
                    if (aula != null) {
                        cell.setBackgroundResource(R.drawable.bg_celula_cor_primaria)
                        cell.setTextColor(Color.BLACK)
                    } else {
                        cell.setBackgroundColor(Color.TRANSPARENT)
                        cell.setTextColor(textColor)
                    }
                    row.addView(cell)
                }
            }

            val aulaSabado = gradeSabado[horaInicio]
            val horarioSabadoTexto = if (aulaSabado != null) "${aulaSabado.horaInicio} - ${aulaSabado.horaFim}" else ""
            createCell(horarioSabadoTexto, isHeader = false).also { cell ->
                if (aulaSabado != null) {
                    cell.setBackgroundResource(R.drawable.bg_celula_cor_primaria)
                    cell.setTextColor(Color.BLACK)
                } else {
                    cell.setBackgroundColor(Color.TRANSPARENT)
                    cell.setTextColor(textColor)
                }
                row.addView(cell)
            }

            createCell(aulaSabado?.codigoDisciplina ?: "", isHeader = false).also { cell ->
                if (aulaSabado != null) {
                    cell.setBackgroundResource(R.drawable.bg_celula_cor_primaria)
                    cell.setTextColor(Color.BLACK)
                } else {
                    cell.setBackgroundColor(Color.TRANSPARENT)
                    cell.setTextColor(textColor)
                }
                row.addView(cell)
            }

            tableHorarios.addView(row)
        }
    }

    private fun mostrarMensagem(mensagem: String) {
        scrollContainer.visibility = View.GONE
        tvMessage.text = mensagem
        messageContainer.visibility = View.VISIBLE
        barOffline.visibility = View.GONE
    }

    private fun createCell(text: String, isHeader: Boolean): TextView {
        return TextView(requireContext()).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (isHeader) 13f else 12f)
            typeface = Typeface.defaultFromStyle(if (isHeader) Typeface.BOLD else Typeface.NORMAL)

            val padH = (6 * resources.displayMetrics.density).toInt()
            val padV = (4 * resources.displayMetrics.density).toInt()
            setPadding(padH, padV, padH, padV)
            setMinWidth((60 * resources.displayMetrics.density).toInt())

            layoutParams = TableRow.LayoutParams(0, TableRow.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(1, 1, 1, 1)
            }

            textAlignment = View.TEXT_ALIGNMENT_CENTER
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
    }
}