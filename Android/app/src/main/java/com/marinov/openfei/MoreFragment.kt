package com.marinov.openfei

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
class MoreFragment : Fragment(), MainActivity.RefreshableFragment {

    private lateinit var ivProfilePhoto: ImageView
    private lateinit var tvStudentName: TextView
    private lateinit var tvStudentRegistration: TextView
    private lateinit var tvStudentClass: TextView
    private lateinit var tvStudentNumber: TextView
    private lateinit var btnReloadProfile: ImageView

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
            (activity as MainActivity).openCustomFragment(BoletosFragment())
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
        view.findViewById<View>(R.id.navigation_provas).setOnClickListener {
        (activity as MainActivity).openCustomFragment(MaterialArquivadoFragment())
        }
        btnReloadProfile.setOnClickListener {
            (activity as? MainActivity)?.openCustomFragment(ProfileFragment())
        }
    }

    private fun openLink(url: String) {
        if (url == "https://interage.fei.org.br/secureserver/portal/graduacao/home"){
            WebViewActivity.start(requireContext(), url)
        } else {
            val webViewFragment = WebViewFragment().apply {
                arguments = WebViewFragment.createArgs(url)
            }
            (activity as? MainActivity)?.openCustomFragment(webViewFragment)
        }
    }

    private fun loadProfile() {
        lifecycleScope.launch {
            try {
                val perfil = Dados.retornaDadosUsuario(online = true)
                updateProfileViews(perfil)
            } catch (_: Exception) {
            } finally {
                stopRefreshing()
            }
        }
    }

    private fun updateProfileViews(perfil: Dados.Perfil) {
        if (!isAdded) return
        tvStudentName.text = perfil.nome.ifBlank { "Faça login para exibir os dados" }
        tvStudentRegistration.text = if (perfil.matricula.isNotBlank()) "Matrícula: ${perfil.matricula}"
        else "--"
        tvStudentClass.text = perfil.curso.ifBlank { "--" }
        if (perfil.email.isNotBlank()) {
            tvStudentNumber.text = perfil.email
            tvStudentNumber.visibility = View.VISIBLE
        } else {
            tvStudentNumber.visibility = View.GONE
        }
    }
}