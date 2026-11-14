package com.marinov.openfei.ui.provas

import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.marinov.openfei.R
import com.marinov.openfei.ui.main.MainActivity
import android.text.util.Linkify
import android.content.Intent
import android.graphics.Paint
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.google.android.material.color.MaterialColors
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.marinov.openfei.data.AnexoMoodle
import com.marinov.openfei.data.CalendarioRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class MateriadeProva : Fragment() {
    companion object {
        private const val ARG_CODIGO = "codigo"
        private const val ARG_NOME = "nome"
        private const val ARG_DATA = "data"
        private const val ARG_TIPO = "tipo"
        private const val ARG_DESCRICAO = "descricao"
        private const val ARG_ANEXOS = "anexos"
    }

    private lateinit var barraCompartilhamento: LinearLayout
    private lateinit var progressBar: View
    private lateinit var txtErro: TextView
    private lateinit var txtTitulo: TextView
    private lateinit var txtConteudo: TextView
    private lateinit var containerAnexos: LinearLayout
    private lateinit var btnWhatsapp: MaterialButton
    private lateinit var btnClaude: MaterialButton
    private lateinit var btnChatgpt: MaterialButton
    private lateinit var btnPerplexity: MaterialButton
    private var codigoDisciplina: String = ""
    private var nomeDisciplina: String = ""
    private var dataProva: String = ""
    private var tipoProva: String = ""
    private var descricao: String = ""
    private var anexos: List<AnexoMoodle> = emptyList()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val root = inflater.inflate(R.layout.fragment_materia_prova, container, false)

        barraCompartilhamento = root.findViewById(R.id.barra_compartilhamento)
        progressBar = root.findViewById(R.id.progress_circular)
        txtErro = root.findViewById(R.id.txt_erro)
        txtTitulo = root.findViewById(R.id.txt_titulo)
        txtConteudo = root.findViewById(R.id.txt_conteudo)
        containerAnexos = root.findViewById(R.id.container_anexos)
        btnWhatsapp = root.findViewById(R.id.btn_whatsapp)
        btnClaude = root.findViewById(R.id.btn_claude)
        btnChatgpt = root.findViewById(R.id.btn_chatgpt)
        btnPerplexity = root.findViewById(R.id.btn_perplexity)

        arguments?.let {
            codigoDisciplina = it.getString(ARG_CODIGO) ?: ""
            nomeDisciplina = it.getString(ARG_NOME) ?: ""
            dataProva = it.getString(ARG_DATA) ?: ""
            tipoProva = it.getString(ARG_TIPO) ?: ""
            descricao = it.getString(ARG_DESCRICAO) ?: ""
            anexos = parseAnexos(it.getString(ARG_ANEXOS))
        }

        exibirConteudo()
        configurarAcoesCompartilhamento()

        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Usa um pequeno delay para garantir que a transição de fragmentos e o layout
        // não engulam o comando de subir a barra de navegação
        view.postDelayed({
            if (isAdded) {
                (activity as? MainActivity)?.showBottomNavigation()
            }
        }, 100)
    }

    private fun exibirConteudo() {
        // Para eventos do Moodle, mostrar apenas o nome do evento como título
        val titulo = if (tipoProva == "Moodle") {
            nomeDisciplina // Apenas o nome do evento
        } else if (nomeDisciplina.isNotBlank()) {
            "$codigoDisciplina - $nomeDisciplina - $dataProva ($tipoProva)"
        } else {
            "$codigoDisciplina - $dataProva ($tipoProva)"
        }

        txtTitulo.text = titulo
        val temDescricaoMoodle = tipoProva == "Moodle" && descricao.isNotBlank()
        txtConteudo.text = when {
            temDescricaoMoodle -> descricao
            tipoProva == "Moodle" -> getString(R.string.materia_sem_descricao)
            else -> getString(R.string.materia_descricao_breve)
        }
        if (temDescricaoMoodle) {
            txtConteudo.setTextIsSelectable(true)
            Linkify.addLinks(txtConteudo, Linkify.WEB_URLS)
        }

        exibirAnexos()

        progressBar.visibility = View.GONE
        txtErro.visibility = View.GONE
        barraCompartilhamento.visibility = View.VISIBLE
        txtTitulo.visibility = View.VISIBLE
        txtConteudo.visibility = View.VISIBLE
    }

    private fun parseAnexos(json: String?): List<AnexoMoodle> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val type = object : TypeToken<List<AnexoMoodle>>() {}.type
            Gson().fromJson<List<AnexoMoodle>>(json, type).orEmpty()
        } catch (_: Exception) {
            emptyList()
        }
    }

    // ------------------------------------------------------------------
    // Anexos (lista de textos clicáveis, mesmo tamanho do texto da descrição)
    // ------------------------------------------------------------------

    private fun exibirAnexos() {
        containerAnexos.removeAllViews()

        if (tipoProva != "Moodle" || anexos.isEmpty()) {
            containerAnexos.visibility = View.GONE
            return
        }

        val ctx = requireContext()
        val corLink = MaterialColors.getColor(containerAnexos, androidx.appcompat.R.attr.colorPrimary)
        val dp = resources.displayMetrics.density

        val outValue = android.util.TypedValue()
        ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)

        containerAnexos.addView(TextView(ctx).apply {
            text = getString(R.string.materia_anexos_titulo)
            textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(MaterialColors.getColor(containerAnexos, com.google.android.material.R.attr.colorOnSurface))
            setLineSpacing(0f, 1.25f)
            setPadding(0, (4 * dp).toInt(), 0, (4 * dp).toInt())
        })

        anexos.forEach { anexo ->
            val tamanho = if (anexo.tamanho > 0) {
                " (${android.text.format.Formatter.formatShortFileSize(ctx, anexo.tamanho)})"
            } else {
                ""
            }

            containerAnexos.addView(TextView(ctx).apply {
                text = "${anexo.nome}$tamanho"
                textSize = 16f
                setLineSpacing(0f, 1.25f)
                setTextColor(corLink)
                paintFlags = paintFlags or Paint.UNDERLINE_TEXT_FLAG
                minHeight = (48 * dp).toInt()
                gravity = android.view.Gravity.CENTER_VERTICAL
                setBackgroundResource(outValue.resourceId)
                isClickable = true
                isFocusable = true
                setOnClickListener { baixarEAbrirAnexo(anexo, this) }
            })
        }

        containerAnexos.visibility = View.VISIBLE
    }

    private fun baixarEAbrirAnexo(anexo: AnexoMoodle, item: View) {
        item.isEnabled = false
        item.alpha = 0.5f
        Toast.makeText(
            requireContext(),
            getString(R.string.materia_anexo_baixando, anexo.nome),
            Toast.LENGTH_SHORT
        ).show()

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val arquivo = CalendarioRepository.baixarAnexoMoodle(anexo)
                if (!isAdded) return@launch
                abrirArquivo(arquivo, anexo)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (isAdded) {
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.materia_anexo_erro, e.message ?: ""),
                        Toast.LENGTH_LONG
                    ).show()
                }
            } finally {
                item.isEnabled = true
                item.alpha = 1f
            }
        }
    }

    private fun mimeDoAnexo(anexo: AnexoMoodle): String {
        val doServidor = anexo.mimeType.lowercase()
        // O Moodle às vezes devolve "document/unknown" ou vazio.
        if (doServidor.isNotBlank() && !doServidor.startsWith("document/") &&
            doServidor != "application/octet-stream"
        ) return doServidor

        val ext = anexo.nome.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
    }

    private fun abrirArquivo(arquivo: java.io.File, anexo: AnexoMoodle) {
        val uri = FileProvider.getUriForFile(
            requireContext(),
            "${requireContext().packageName}.fileprovider",
            arquivo
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeDoAnexo(anexo))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(
                requireContext(),
                getString(R.string.materia_anexo_sem_app),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun configurarAcoesCompartilhamento() {
        btnWhatsapp.setOnClickListener { compartilharConteudo("com.whatsapp") }
        btnClaude.setOnClickListener { compartilharConteudo("com.anthropic.claude") }
        btnChatgpt.setOnClickListener { compartilharConteudo("com.openai.chatgpt") }
        btnPerplexity.setOnClickListener { compartilharConteudo("ai.perplexity.app.android") }
    }

    private fun compartilharConteudo(pacoteApp: String) {
        try {
            val texto = if (pacoteApp == "com.whatsapp") {
                "${txtTitulo.text}\n${txtConteudo.text}"          // ★ BUG 79: "\n" de verdade
            } else {
                getString(
                    R.string.materia_resumo_prompt,
                    txtTitulo.text.toString(),
                    txtConteudo.text.toString()
                )
            }

            val sendIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(android.content.Intent.EXTRA_TEXT, texto)
            }

            if (isAppInstalled(pacoteApp)) {
                sendIntent.`package` = pacoteApp
                startActivity(sendIntent)
            } else {
                startActivity(
                    android.content.Intent.createChooser(
                        sendIntent, getString(R.string.compartilhar_via)
                    )
                )
            }
        } catch (e: Exception) {
            android.util.Log.e("Compartilhamento", getString(R.string.materia_erro_compartilhar, e.message ?: ""))
        }
    }

    private fun isAppInstalled(packageName: String): Boolean = try {
        val pm = requireContext().packageManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getApplicationInfo(
                packageName,
                android.content.pm.PackageManager.ApplicationInfoFlags.of(0)
            )
        } else {
            @Suppress("DEPRECATION")
            pm.getApplicationInfo(packageName, 0)
        }
        true
    } catch (_: android.content.pm.PackageManager.NameNotFoundException) {
        false
    }
}