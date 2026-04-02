package com.marinov.colegioetapa

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class ProvasWidgetDark : AppWidgetProvider() {

    companion object {
        private const val PREFS_WIDGET = "widget_provas_prefs"
        private const val KEY_PROVAS = "provas_data"
        private const val TAG = "ProvasWidgetDark"
        private val WEEK_IDS = listOf(
            R.id.week1, R.id.week2, R.id.week3, R.id.week4, R.id.week5, R.id.week6
        )
        private fun updateAppWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int
        ) {
            try {
                val views = buildRemoteViews(context)
                val intent = Intent(context, MainActivity::class.java).apply {
                    putExtra("destination", "provas")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                }

                val pendingIntent = PendingIntent.getActivity(
                    context,
                    0,
                    intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                views.setOnClickPendingIntent(R.id.widget_root, pendingIntent)
                appWidgetManager.updateAppWidget(appWidgetId, views)
            } catch (e: Exception) {
                Log.e(TAG, "Falha ao construir widget", e)
                val errorViews = RemoteViews(context.packageName, R.layout.widget_error_dark)
                errorViews.setTextViewText(R.id.widget_error_text, "Erro ao carregar")
                appWidgetManager.updateAppWidget(appWidgetId, errorViews)
            }
        }

        private fun buildRemoteViews(context: Context): RemoteViews {
            return try {
                val views = RemoteViews(context.packageName, R.layout.widget_calendar_provas_dark)
                val calendar = Calendar.getInstance()
                val mesAno = SimpleDateFormat("MMMM yyyy", Locale("pt", "BR")).format(calendar.time)
                views.setTextViewText(R.id.txt_mes_ano, mesAno.replaceFirstChar { it.titlecase() })

                // Carregar dados
                val prefs = context.getSharedPreferences(PREFS_WIDGET, Context.MODE_PRIVATE)
                val provasJson = prefs.getString(KEY_PROVAS, null)
                Log.d(TAG, "Dados salvos: $provasJson")

                val provasMap = mutableMapOf<String, MutableList<ProvaData>>()
                if (provasJson != null) {
                    try {
                        val jsonArray = JSONArray(provasJson)
                        for (i in 0 until jsonArray.length()) {
                            val prova = jsonArray.getJSONObject(i)
                            val data = prova.getString("data")
                            val codigo = prova.getString("codigo")
                            val tipo = prova.getString("tipo")

                            val lista = provasMap.getOrPut(data) { mutableListOf() }
                            lista.add(ProvaData(codigo, tipo))
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Erro ao analisar JSON", e)
                    }
                }
                Log.d(TAG, "Provas mapeadas: ${provasMap.size} dias")

                val diaAtual = calendar.get(Calendar.DAY_OF_MONTH)
                val mesAtual = calendar.get(Calendar.MONTH)  // Janeiro=0
                val anoAtual = calendar.get(Calendar.YEAR)

                // Construir cabeçalho
                val diasSemana = listOf("D", "S", "T", "Q", "Q", "S", "S")
                views.removeAllViews(R.id.header_container)
                for (dia in diasSemana) {
                    val headerView = RemoteViews(context.packageName, R.layout.widget_dia_header_dark)
                    headerView.setTextViewText(R.id.txt_dia_semana, dia)
                    views.addView(R.id.header_container, headerView)
                }

                // Calcular início e fim do calendário
                calendar.set(Calendar.DAY_OF_MONTH, 1)
                val primeiroDia = calendar.get(Calendar.DAY_OF_WEEK)
                val offset = (primeiroDia - Calendar.SUNDAY + 7) % 7

                var diaGlobal = 1

                for (weekId in WEEK_IDS) {
                    views.removeAllViews(weekId)
                    for (i in 0 until 7) {
                        val cellIndex = diaGlobal - 1
                        val diaCal = Calendar.getInstance().apply {
                            time = calendar.time
                            add(Calendar.DAY_OF_MONTH, cellIndex - offset)
                        }

                        val dia = diaCal.get(Calendar.DAY_OF_MONTH)
                        val mes = diaCal.get(Calendar.MONTH)
                        val ano = diaCal.get(Calendar.YEAR)
                        val isMesAtual = mes == mesAtual && ano == anoAtual
                        val isHoje = isMesAtual && dia == diaAtual

                        // Formatar data no padrão "dd/MM/yyyy" para buscar provas
                        val chaveData = String.format(Locale.getDefault(), "%02d/%02d/%d", dia, mes + 1, ano)
                        val provasDia = provasMap[chaveData] ?: emptyList()

                        val cellView = createDayCell(
                            context,
                            dia,
                            isMesAtual,
                            isHoje,
                            provasDia
                        )
                        views.addView(weekId, cellView)
                        diaGlobal++
                    }
                }
                views
            } catch (e: Exception) {
                Log.e(TAG, "Erro ao construir RemoteViews", e)
                RemoteViews(context.packageName, R.layout.widget_error_dark).apply {
                    setTextViewText(R.id.widget_error_text, "Erro: ${e.localizedMessage}")
                }
            }
        }

        private fun createDayCell(
            context: Context,
            dia: Int,
            isMesAtual: Boolean,
            isHoje: Boolean,
            provas: List<ProvaData>
        ): RemoteViews {
            val cellView = RemoteViews(context.packageName, R.layout.widget_dia_item_dark)
            cellView.setTextViewText(R.id.txt_dia, dia.toString())

            // Remover todas as views antigas do container de provas
            cellView.removeAllViews(R.id.provas_container)

            // Aplicar estilo baseado no mês
            if (isMesAtual) {
                cellView.setTextColor(R.id.txt_dia, ContextCompat.getColor(context, R.color.white))
                if (isHoje) {
                    cellView.setInt(R.id.txt_dia, "setBackgroundResource", R.drawable.bg_dia_atual)
                    cellView.setTextColor(R.id.txt_dia, ContextCompat.getColor(context, R.color.white))
                }
            } else {
                cellView.setTextColor(R.id.txt_dia, ContextCompat.getColor(context, R.color.text_secondary))
            }

            // Processar provas
            if (provas.isNotEmpty()) {
                cellView.setViewVisibility(R.id.provas_container, View.VISIBLE)

                // Adicionar cada prova como uma linha separada
                for (prova in provas) {
                    val provaView = RemoteViews(context.packageName, R.layout.widget_prova_item)

                    provaView.setTextViewText(R.id.txt_prova_codigo, prova.codigo)

                    // Determinar cor com base no tipo de prova
                    val bgRes = if (prova.tipo == "REC") {
                        R.drawable.bg_prova_recuperacao
                    } else {
                        R.drawable.bg_prova_normal
                    }

                    provaView.setInt(R.id.prova_container, "setBackgroundResource", bgRes)
                    if (prova.tipo == "REC") {
                        provaView.setTextColor(R.id.txt_prova_codigo, ContextCompat.getColor(context, R.color.bootstrap_dark))
                    } else {
                        provaView.setTextColor(R.id.txt_prova_codigo, ContextCompat.getColor(context, R.color.white))
                    }

                    // Adicionar ao container
                    cellView.addView(R.id.provas_container, provaView)
                }
            } else {
                cellView.setViewVisibility(R.id.provas_container, View.GONE)
            }

            return cellView
        }

        private data class ProvaData(val codigo: String, val tipo: String)
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (appWidgetId in appWidgetIds) {
            updateAppWidget(context, appWidgetManager, appWidgetId)
        }
    }
}