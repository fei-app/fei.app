package com.marinov.openfei.ui.moodle

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.marinov.openfei.data.Dados
import com.marinov.openfei.data.SessionExpiredException
import com.marinov.openfei.ui.login.LoginActivity
import com.marinov.openfei.ui.main.MainActivity
import com.marinov.openfei.ui.webview.WebViewFragment
import kotlinx.coroutines.launch

/**
 * Fragmento responsável por abrir o Moodle no WebViewFragment.
 * Atua como um ponto de entrada dedicado (ex: para Bottom Navigation ou Menu),
 * delegando a exibição ao WebViewFragment, conforme o padrão usado no MoreFragment.
 *
 * ★ NOVO: antes de abrir o WebView, renova obrigatoriamente a sessão (FEI + Moodle),
 * reutilizando Dados.garantirSessaoValida() — o mesmo mecanismo já usado antes de cada
 * request de dados do portal. ★
 */
class MoodleFragment : Fragment() {

    private val moodleUrl = "https://moodle.fei.edu.br/my/"

    // ★ Flag para evitar que o WebView seja reaberto se este fragment for restaurado do backstack ★
    private var hasLaunchedWebView = false

    private lateinit var rootContainer: FrameLayout
    private lateinit var progressBar: ProgressBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Restaura o estado da flag caso o app seja morto em segundo plano e restaurado
        hasLaunchedWebView = savedInstanceState?.getBoolean("HAS_LAUNCHED", false) ?: false
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        rootContainer = FrameLayout(requireContext()).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // ★ NOVO: indicador de progresso exibido enquanto a sessão é renovada ★
        progressBar = ProgressBar(requireContext()).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        }
        rootContainer.addView(progressBar)

        return rootContainer
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (hasLaunchedWebView) return

        // ★ NOVO: renova a sessão (FEI + Moodle) obrigatoriamente antes de abrir o WebView,
        // seguindo o mesmo padrão de Dados.renewSession() ★
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                Dados.garantirSessaoValida()
                if (!isAdded) return@launch

                hasLaunchedWebView = true
                val webViewFragment = WebViewFragment().apply {
                    arguments = WebViewFragment.createArgs(moodleUrl, exitToHome = true)
                }
                (activity as? MainActivity)?.openCustomFragment(webViewFragment)
            } catch (_: SessionExpiredException) {
                if (!isAdded) return@launch
                Toast.makeText(requireContext(), "Sessão expirada. Faça login novamente.", Toast.LENGTH_LONG).show()
                val intent = Intent(requireContext(), LoginActivity::class.java)
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                startActivity(intent)
                requireActivity().finish()
            } catch (_: Exception) {
                if (!isAdded) return@launch
                Toast.makeText(requireContext(), "Não foi possível abrir o Moodle. Tente novamente.", Toast.LENGTH_LONG).show()
                (activity as? MainActivity)?.navigateToHome()
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("HAS_LAUNCHED", hasLaunchedWebView)
    }
}