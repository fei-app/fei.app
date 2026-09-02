package com.marinov.openfei.ui.profile

import android.annotation.SuppressLint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import com.marinov.openfei.R
import com.marinov.openfei.data.Perfil
import com.marinov.openfei.data.PerfilRepository
import com.marinov.openfei.ui.main.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ProfileFragment : Fragment(), MainActivity.RefreshableFragment {
    private lateinit var profileContainer: LinearLayout
    private lateinit var layoutSemInternet: LinearLayout
    private lateinit var btnTentarNovamente: MaterialButton
    private lateinit var profileCard: MaterialCardView
    private lateinit var ivProfilePhoto: ImageView
    private val handler = Handler(Looper.getMainLooper())
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

    private fun loadProfile() {
        lifecycleScope.launch {
            val mainActivity = activity as? MainActivity ?: return@launch
            val status = mainActivity.checkConnectionAndSession()
            when (status) {
                MainActivity.STATUS_OFFLINE, MainActivity.STATUS_LOGIN_NEEDED -> {
                    showOfflineUI()
                }
                MainActivity.STATUS_ONLINE_OK -> {
                    try {
                        val perfil = PerfilRepository.retornaDadosUsuario(online = true)
                        withContext(Dispatchers.Main) {
                            displayProfileData(perfil)
                            profileCard.visibility = View.VISIBLE
                            layoutSemInternet.visibility = View.GONE
                        }
                    } catch (_: Exception) {
                        showOfflineUI()
                    }
                }
            }
            if (isRefreshing) {
                mainActivity.setRefreshing(false)
                isRefreshing = false
            }
        }
    }

    private fun showOfflineUI() {
        handler.post {
            if (!isAdded) return@post
            profileCard.visibility = View.GONE
            layoutSemInternet.visibility = View.VISIBLE
        }
    }

    private fun displayProfileData(perfil: Perfil) {
        if (!isAdded) return

        profileContainer.removeAllViews()

        addProfileItem(getString(R.string.perfil_nome), perfil.nome)
        addProfileItem(getString(R.string.perfil_matricula), perfil.matricula)
        addProfileItem(getString(R.string.perfil_curso), perfil.curso)

        if (perfil.email.isNotBlank()) {
            addProfileItem(getString(R.string.perfil_email), perfil.email)
        }
    }

    @SuppressLint("SetTextI18n")
    private fun addProfileItem(label: String, value: String) {
        val context = context ?: return
        if (!isAdded) return
        val itemView = LayoutInflater.from(context).inflate(R.layout.item_profile, profileContainer, false)
        val labelView: TextView = itemView.findViewById(R.id.itemLabel)
        val valueView: TextView = itemView.findViewById(R.id.itemValue)
        labelView.text = "$label:"
        valueView.text = value
        profileContainer.addView(itemView)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        handler.removeCallbacksAndMessages(null)
    }
}