package com.marinov.openfei.ui.profile

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.marinov.openfei.R
import com.marinov.openfei.data.Perfil
import com.marinov.openfei.data.PerfilRepository
import com.marinov.openfei.ui.main.MainActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ProfileFragment : Fragment(), MainActivity.RefreshableFragment {

    private lateinit var profileContainer: LinearLayout
    private lateinit var layoutSemInternet: LinearLayout
    private lateinit var btnTentarNovamente: MaterialButton
    private lateinit var profileCard: MaterialCardView
    private lateinit var ivProfilePhoto: ImageView
    private lateinit var progressBar: CircularProgressIndicator

    private var perfilAtual: Perfil? = null
    private var isRefreshing = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_profile, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        profileContainer = view.findViewById(R.id.profileContainer)
        layoutSemInternet = view.findViewById(R.id.layout_sem_internet)
        btnTentarNovamente = view.findViewById(R.id.btn_tentar_novamente)
        profileCard = view.findViewById(R.id.profileCard)
        ivProfilePhoto = view.findViewById(R.id.iv_profile_photo)
        progressBar = view.findViewById(R.id.progress_circular)

        ivProfilePhoto.setImageResource(R.drawable.ic_person)

        btnTentarNovamente.setOnClickListener {
            loadProfile()
        }

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
                displayProfileData(cached)
                exibirConteudo()
            } else {
                exibirCarregando()
            }

            val mainActivity = activity as? MainActivity
            if (mainActivity == null) {
                if (!temCache) exibirOfflineUI()
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
                        exibirOfflineUI()
                    }
                    stopRefreshing()
                }
            }
        }
    }

    private suspend fun atualizarPerfilOnline() {
        try {
            val online = PerfilRepository.obterPerfilOnlineOrNull()
            if (!isAdded) return

            if (online != null && online.temDados()) {
                val mudou = online != perfilAtual
                perfilAtual = online

                if (mudou) {
                    displayProfileData(online)
                }

                exibirConteudo()
            } else {
                if (perfilAtual == null || !perfilAtual!!.temDados()) {
                    exibirOfflineUI()
                } else {
                    exibirConteudo()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            if (!isAdded) return

            if (perfilAtual == null || !perfilAtual!!.temDados()) {
                exibirOfflineUI()
            } else {
                exibirConteudo()
            }
        } finally {
            stopRefreshing()
        }
    }

    private fun exibirCarregando() {
        progressBar.visibility = View.VISIBLE
        profileCard.visibility = View.GONE
        layoutSemInternet.visibility = View.GONE
    }

    private fun exibirConteudo() {
        progressBar.visibility = View.GONE
        profileCard.visibility = View.VISIBLE
        layoutSemInternet.visibility = View.GONE
    }

    private fun exibirOfflineUI() {
        progressBar.visibility = View.GONE
        profileCard.visibility = View.GONE
        layoutSemInternet.visibility = View.VISIBLE
    }

    private fun displayProfileData(perfil: Perfil) {
        if (!isAdded) return

        profileContainer.removeAllViews()

        addProfileItem(
            getString(R.string.perfil_nome),
            perfil.nome.ifBlank { getString(R.string.perfil_sem_dados) }
        )

        addProfileItem(
            getString(R.string.perfil_matricula),
            perfil.matricula.ifBlank { getString(R.string.perfil_traco) }
        )

        addProfileItem(
            getString(R.string.perfil_curso),
            perfil.curso.ifBlank { getString(R.string.perfil_traco) }
        )

        if (perfil.email.isNotBlank()) {
            addProfileItem(getString(R.string.perfil_email), perfil.email)
        }
    }

    @SuppressLint("SetTextI18n")
    private fun addProfileItem(label: String, value: String) {
        val context = context ?: return
        if (!isAdded) return

        val itemView = LayoutInflater.from(context)
            .inflate(R.layout.item_profile, profileContainer, false)

        val labelView: TextView = itemView.findViewById(R.id.itemLabel)
        val valueView: TextView = itemView.findViewById(R.id.itemValue)

        labelView.text = "$label:"
        valueView.text = value

        profileContainer.addView(itemView)
    }

    private fun Perfil.temDados(): Boolean {
        return nome.isNotBlank() ||
                matricula.isNotBlank() ||
                curso.isNotBlank() ||
                email.isNotBlank()
    }
}