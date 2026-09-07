package com.marinov.openfei.ui.more

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.marinov.openfei.R
import com.marinov.openfei.data.Perfil
import com.marinov.openfei.data.PerfilRepository
import com.marinov.openfei.ui.boletos.BoletosFragment
import com.marinov.openfei.ui.main.MainActivity
import com.marinov.openfei.ui.profile.ProfileFragment
import com.marinov.openfei.ui.webview.WebViewActivity
import com.marinov.openfei.ui.webview.WebViewFragment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MoreFragment : Fragment(), MainActivity.RefreshableFragment {

    private lateinit var ivProfilePhoto: ImageView
    private lateinit var tvStudentName: TextView
    private lateinit var tvStudentRegistration: TextView
    private lateinit var tvStudentClass: TextView
    private lateinit var tvStudentNumber: TextView
    private lateinit var btnReloadProfile: ImageView
    private lateinit var profileProgress: CircularProgressIndicator

    private var perfilAtual: Perfil? = null
    private var isRefreshing = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? = inflater.inflate(R.layout.fragment_more, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        (activity as? MainActivity)?.showBottomNavigation()

        initViews(view)
        setupClickListeners(view)

        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    (activity as? MainActivity)?.navigateToHome()
                }
            }
        )

        loadProfile()
    }

    override fun onRefresh() {
        isRefreshing = true
        loadProfile()
    }

    private fun stopRefreshing() {
        if (isRefreshing) {
            (activity as? MainActivity)?.setRefreshing(false)
            isRefreshing = false
        }
    }

    private fun initViews(view: View) {
        ivProfilePhoto = view.findViewById(R.id.iv_profile_photo)
        tvStudentName = view.findViewById(R.id.tv_student_name)
        tvStudentRegistration = view.findViewById(R.id.tv_student_registration)
        tvStudentClass = view.findViewById(R.id.tv_student_class)
        tvStudentNumber = view.findViewById(R.id.tv_student_number)
        btnReloadProfile = view.findViewById(R.id.btn_reload_profile)
        profileProgress = view.findViewById(R.id.profile_progress)

        ivProfilePhoto.setImageResource(R.drawable.ic_person)
    }

    private fun setupClickListeners(view: View) {
        view.findViewById<View>(R.id.option_faltas).setOnClickListener {
            openLink("https://interage.fei.org.br/secureserver/portal/graduacao/secretaria/consultas/faltas")
        }

        view.findViewById<View>(R.id.option_mudanca_horario).setOnClickListener {
            openLink("https://interage.fei.org.br/secureserver/portal/graduacao/secretaria/guiche-online/mudanca-de-horario")
        }

        view.findViewById<View>(R.id.option_escolha_area).setOnClickListener {
            openLink("https://interage.fei.org.br/secureserver/portal/graduacao/secretaria/guiche-online/escolha-de-area-e-enfase")
        }

        view.findViewById<View>(R.id.option_dados_pessoais).setOnClickListener {
            openLink("https://interage.fei.org.br/secureserver/portal/graduacao/secretaria/dados-pessoais")
        }

        view.findViewById<View>(R.id.option_alterar_senha).setOnClickListener {
            openLink("https://interage.fei.org.br/secureserver/portal/graduacao/cgi/conta/senha")
        }

        view.findViewById<View>(R.id.option_boletos).setOnClickListener {
            (activity as? MainActivity)?.openCustomFragment(BoletosFragment())
        }

        view.findViewById<View>(R.id.option_persenca).setOnClickListener {
            openLink("https://interage.fei.org.br/secureserver/portal/graduacao/sala-dos-professores/aulas/presenca")
        }

        view.findViewById<View>(R.id.option_solicitacao_documentos).setOnClickListener {
            openLink("https://interage.fei.org.br/secureserver/portal/graduacao/secretaria/guiche-online/solicitacao-de-documentos")
        }

        view.findViewById<View>(R.id.option_atividades_complementares).setOnClickListener {
            openLink("https://interage.fei.org.br/secureserver/portal/graduacao/sala-dos-professores/atividades-complementares")
        }

        view.findViewById<View>(R.id.option_curso_de_ferias).setOnClickListener {
            openLink("https://interage.fei.org.br/secureserver/portal/graduacao/eventos/temporarios-e-sazonais/cursos-de-ferias")
        }

        view.findViewById<View>(R.id.option_site_completo).setOnClickListener {
            openLink("https://interage.fei.org.br/secureserver/portal/graduacao/home")
        }

        btnReloadProfile.setOnClickListener {
            (activity as? MainActivity)?.openCustomFragment(ProfileFragment())
        }
    }

    private fun openLink(url: String) {
        lifecycleScope.launch {
            try {
                (activity as? MainActivity)?.setRefreshing(true)
                if (url == "https://interage.fei.org.br/secureserver/portal/graduacao/home") {
                    WebViewActivity.start(requireContext(), url)
                } else {
                    val webViewFragment = WebViewFragment().apply {
                        arguments = WebViewFragment.createArgs(url)
                    }
                    (activity as? MainActivity)?.openCustomFragment(webViewFragment)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                (activity as? MainActivity)?.setRefreshing(false)
            }
        }
    }

    private fun loadProfile() {
        lifecycleScope.launch {
            // 1. Prioriza cache local para montar a UI imediatamente
            val cached = withContext(Dispatchers.IO) {
                PerfilRepository.obterPerfilCache()
            }

            if (!isAdded) return@launch

            perfilAtual = cached
            val temCache = cached != null && cached.temDados()

            if (temCache) {
                updateProfileViews(cached)
                esconderLoadingPerfil()
            } else {
                exibirLoadingPerfil()
            }

            val mainActivity = activity as? MainActivity
            if (mainActivity == null) {
                if (!temCache) exibirPerfilVazio()
                esconderLoadingPerfil()
                stopRefreshing()
                return@launch
            }

            val status = mainActivity.checkConnectionAndSession()
            if (!isAdded) return@launch

            when (status) {
                MainActivity.STATUS_ONLINE_OK -> {
                    atualizarPerfilOnline()
                }
                else -> {
                    if (!temCache) {
                        exibirPerfilVazio()
                    }
                    esconderLoadingPerfil()
                    stopRefreshing()
                }
            }
        }
    }

    private suspend fun atualizarPerfilOnline() {
        try {
            val online = PerfilRepository.obterPerfilOnlineOrNull()
            if (!isAdded) return

            esconderLoadingPerfil()

            if (online != null && online.temDados()) {
                val mudou = online != perfilAtual
                perfilAtual = online

                if (mudou) {
                    updateProfileViews(online)
                }
            } else {
                if (perfilAtual == null || !perfilAtual!!.temDados()) {
                    exibirPerfilVazio()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            if (!isAdded) return
            esconderLoadingPerfil()

            if (perfilAtual == null || !perfilAtual!!.temDados()) {
                exibirPerfilVazio()
            }
        } finally {
            stopRefreshing()
        }
    }

    private fun updateProfileViews(perfil: Perfil) {
        if (!isAdded) return

        tvStudentName.text = perfil.nome.ifBlank { getString(R.string.perfil_sem_dados) }

        tvStudentRegistration.text = if (perfil.matricula.isNotBlank()) {
            getString(R.string.perfil_matricula_formato, perfil.matricula)
        } else {
            getString(R.string.perfil_traco)
        }

        tvStudentClass.text = perfil.curso.ifBlank { getString(R.string.perfil_traco) }

        if (perfil.email.isNotBlank()) {
            tvStudentNumber.text = perfil.email
            tvStudentNumber.visibility = View.VISIBLE
        } else {
            tvStudentNumber.visibility = View.GONE
        }
    }

    private fun exibirLoadingPerfil() {
        profileProgress.visibility = View.VISIBLE
        btnReloadProfile.visibility = View.INVISIBLE
    }

    private fun esconderLoadingPerfil() {
        profileProgress.visibility = View.GONE
        btnReloadProfile.visibility = View.VISIBLE
    }

    private fun exibirPerfilVazio() {
        updateProfileViews(Perfil("", "", "", ""))
    }

    private fun Perfil.temDados(): Boolean {
        return nome.isNotBlank() ||
                matricula.isNotBlank() ||
                curso.isNotBlank() ||
                email.isNotBlank()
    }
}