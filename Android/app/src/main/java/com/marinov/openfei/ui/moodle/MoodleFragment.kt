package com.marinov.openfei.ui.moodle

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.marinov.openfei.data.SessionExpiredException
import com.marinov.openfei.ui.login.LoginActivity
import com.marinov.openfei.ui.main.MainActivity
import com.marinov.openfei.ui.webview.WebViewFragment
import kotlinx.coroutines.launch

class MoodleFragment : Fragment() {
    private val moodleUrl = "https://moodle.fei.edu.br/my/"
    private var hasLaunchedWebView = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hasLaunchedWebView = savedInstanceState?.getBoolean("HAS_LAUNCHED", false) ?: false
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return View(requireContext())
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (hasLaunchedWebView) return
        run()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("HAS_LAUNCHED", hasLaunchedWebView)
    }

    private fun run() {
        if (hasLaunchedWebView) return

        val mainActivity = activity as? MainActivity ?: return

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                if (!isAdded) return@launch
                hasLaunchedWebView = true
                val webViewFragment = WebViewFragment().apply {
                    arguments = WebViewFragment.createArgs(moodleUrl, exitToHome = true)
                }
                mainActivity.openCustomFragment(webViewFragment)

            } catch (_: SessionExpiredException) {
                if (!isAdded) return@launch
                val intent = Intent(requireContext(), LoginActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                }
                startActivity(intent)
                requireActivity().finish()
            }
        }
    }
}