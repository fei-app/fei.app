package com.marinov.openfei

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.CircularProgressIndicator

class MateriadeProva : Fragment() {

    companion object {
        private const val ARG_CODIGO = "codigo"
        private const val ARG_NOME = "nome"
        private const val ARG_DATA = "data"
        private const val ARG_TIPO = "tipo"
    }

    private lateinit var barraCompartilhamento: LinearLayout
    private lateinit var progressBar: CircularProgressIndicator
    private lateinit var txtErro: TextView
    private lateinit var txtTitulo: TextView
    private lateinit var txtConteudo: TextView

    private lateinit var btnWhatsapp: MaterialButton
    private lateinit var btnClaude: MaterialButton
    private lateinit var btnChatgpt: MaterialButton
    private lateinit var btnPerplexity: MaterialButton

    private var codigoDisciplina: String = ""
    private var nomeDisciplina: String = ""
    private var dataProva: String = ""
    private var tipoProva: String = ""

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

        btnWhatsapp = root.findViewById(R.id.btn_whatsapp)
        btnClaude = root.findViewById(R.id.btn_claude)
        btnChatgpt = root.findViewById(R.id.btn_chatgpt)
        btnPerplexity = root.findViewById(R.id.btn_perplexity)

        arguments?.let {
            codigoDisciplina = it.getString(ARG_CODIGO) ?: ""
            nomeDisciplina = it.getString(ARG_NOME) ?: ""
            dataProva = it.getString(ARG_DATA) ?: ""
            tipoProva = it.getString(ARG_TIPO) ?: ""
        }

        exibirConteudo()
        configurarAcoesCompartilhamento()

        return root
    }

    private fun exibirConteudo() {
        // Formata o título: "CCP010 - Algoritmos e Programação - 01/04 (P1)"
        val titulo = if (nomeDisciplina.isNotBlank()) {
            "$codigoDisciplina - $nomeDisciplina - $dataProva ($tipoProva)"
        } else {
            "$codigoDisciplina - $dataProva ($tipoProva)"
        }
        txtTitulo.text = titulo

        val descricao = "Em breve será possível ver uma breve descrição da prova."
        txtConteudo.text = descricao

        progressBar.visibility = View.GONE
        txtErro.visibility = View.GONE
        barraCompartilhamento.visibility = View.VISIBLE
        txtTitulo.visibility = View.VISIBLE
        txtConteudo.visibility = View.VISIBLE
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
                "${txtTitulo.text}\n\n${txtConteudo.text}"
            } else {
                "Faça um resumo para minha prova: ${txtTitulo.text}\n\n${txtConteudo.text}"
            }

            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(android.content.Intent.EXTRA_TEXT, texto)
                `package` = pacoteApp
            }

            if (isAppInstalled(pacoteApp)) {
                startActivity(intent)
            } else {
                val shareIntent = android.content.Intent.createChooser(intent, "Compartilhar via")
                startActivity(shareIntent)
            }
        } catch (e: Exception) {
            android.util.Log.e("Compartilhamento", "Erro: ${e.message}")
        }
    }

    private fun isAppInstalled(packageName: String): Boolean {
        return try {
            requireContext().packageManager.getApplicationInfo(packageName, 0)
            true
        } catch (_: android.content.pm.PackageManager.NameNotFoundException) {
            false
        }
    }
}