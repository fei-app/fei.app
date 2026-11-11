package com.marinov.openfei.ui.login

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.text.method.PasswordTransformationMethod
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.marinov.openfei.R
import com.marinov.openfei.background.SyncScheduler
import com.marinov.openfei.login.CredentialsStore
import com.marinov.openfei.login.LoginLogic
import com.marinov.openfei.ui.main.MainActivity
import com.marinov.openfei.util.PermissionHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.core.content.edit
import com.marinov.openfei.login.AuthState

class LoginActivity : AppCompatActivity() {

    companion object {
        /** ★ BUG 54: quando a Main manda voltar por sessão expirada, não autologamos. */
        const val EXTRA_FROM_SESSION_EXPIRY = "extra_from_session_expiry"

        private const val AUTO_LOGIN_PREFS = "login_auto_prefs"
        private const val KEY_LAST_AUTO_LOGIN = "last_auto_login_attempt"
        private const val AUTO_LOGIN_COOLDOWN_MS = 60_000L

        @Deprecated("Use CredentialsStore.prefs(context) — pode retornar null com segurança",
            ReplaceWith(
                "CredentialsStore.prefs(context)",
                "com.marinov.openfei.login.CredentialsStore"
            )
        )
        fun getEncryptedPrefs(context: Context): SharedPreferences? =
            CredentialsStore.prefs(context)
    }

    private lateinit var etUser: TextInputEditText
    private lateinit var etPass: TextInputEditText
    private lateinit var tilUser: TextInputLayout
    private lateinit var tilPass: TextInputLayout
    private lateinit var btnLogin: MaterialButton
    private lateinit var progressIndicator: CircularProgressIndicator

    private var isPasswordVisible = false
    private var isLoggingIn = false

    /** ★ BUG 22: DEVICE_CREDENTIAL só existe na API 30+. Abaixo disso usamos
     *  BIOMETRIC_WEAK (digital) e, se não houver biometria, o keyguard do sistema. */
    private val allowedAuthenticators: Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) BIOMETRIC_STRONG or DEVICE_CREDENTIAL
        else BIOMETRIC_WEAK

    private val keyguardLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) togglePasswordVisibility()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        PermissionHelper.solicitarPermissoesIniciais(this)

        etUser = findViewById(R.id.et_user)
        etPass = findViewById(R.id.et_password)
        tilUser = findViewById(R.id.til_user)
        tilPass = findViewById(R.id.til_password)
        btnLogin = findViewById(R.id.btn_login)
        progressIndicator = findViewById(R.id.progress_indicator)

        // ★ BUG 6: nunca mais crasha se o storage criptografado estiver corrompido.
        loadSavedCredentials()
        setupPasswordToggle()

        btnLogin.setOnClickListener { initiateLoginFlow(autoLogin = false) }

        val fromExpiry = intent.getBooleanExtra(EXTRA_FROM_SESSION_EXPIRY, false)
        when {
            fromExpiry -> Toast.makeText(
                this, getString(R.string.login_conta_reconectar), Toast.LENGTH_LONG
            ).show()

            CredentialsStore.hasCredentials(this) && !isAutoLoginInCooldown() ->
                initiateLoginFlow(autoLogin = true)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        PermissionHelper.onPermissionResult(this, requestCode)
    }

    // ------------------------------------------------------------------
    // Login
    // ------------------------------------------------------------------

    private fun initiateLoginFlow(autoLogin: Boolean) {
        if (isLoggingIn) return
        if (autoLogin) registerAutoLoginAttempt()

        val user = etUser.text.toString().trim()
        val pass = etPass.text.toString().trim()

        tilUser.error = null
        tilPass.error = null

        if (user.isEmpty()) {
            tilUser.error = getString(R.string.login_preencha_usuario)
            return
        }
        if (pass.isEmpty()) {
            tilPass.error = getString(R.string.login_preencha_senha)
            return
        }

        isLoggingIn = true
        setLoading(true)

        lifecycleScope.launch {
            val result = LoginLogic.performLogin(user, pass, this@LoginActivity)
            withContext(Dispatchers.Main) {
                isLoggingIn = false
                if (isFinishing || isDestroyed) return@withContext
                if (result.success) {
                    AuthState.reset(applicationContext)
                    SyncScheduler.scheduleAll(applicationContext)
                    SyncScheduler.syncNow(applicationContext, SyncScheduler.TYPE_BOOT)
                    startMainActivity()
                } else {
                    setLoading(false)
                    Toast.makeText(this@LoginActivity, result.errorMessage, Toast.LENGTH_LONG)
                        .show()
                }
            }
        }
    }

    private fun startMainActivity() {
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
        )
        finish()
    }

    private fun setLoading(isLoading: Boolean) {
        if (isLoading) {
            btnLogin.text = ""
            btnLogin.isEnabled = false
            progressIndicator.visibility = View.VISIBLE
        } else {
            btnLogin.text = getString(R.string.login_entrar)
            btnLogin.isEnabled = true
            progressIndicator.visibility = View.GONE
        }
    }

    // ------------------------------------------------------------------
    // Credenciais
    // ------------------------------------------------------------------

    private fun loadSavedCredentials() {
        val savedUser = CredentialsStore.user(this)
        val savedPass = CredentialsStore.pass(this)
        if (savedUser.isNotEmpty()) etUser.setText(savedUser)
        if (savedPass.isNotEmpty()) etPass.setText(savedPass)
    }

    private fun isAutoLoginInCooldown(): Boolean {
        val prefs = getSharedPreferences(AUTO_LOGIN_PREFS, MODE_PRIVATE)
        val last = prefs.getLong(KEY_LAST_AUTO_LOGIN, 0L)
        return System.currentTimeMillis() - last < AUTO_LOGIN_COOLDOWN_MS
    }

    private fun registerAutoLoginAttempt() {
        getSharedPreferences(AUTO_LOGIN_PREFS, MODE_PRIVATE)
            .edit {
                putLong(KEY_LAST_AUTO_LOGIN, System.currentTimeMillis())
            }
    }

    // ------------------------------------------------------------------
    // Biometria / visibilidade da senha
    // ------------------------------------------------------------------

    private fun setupPasswordToggle() {
        tilPass.setEndIconOnClickListener {
            if (isPasswordVisible) {
                togglePasswordVisibility()
                return@setEndIconOnClickListener
            }
            val biometricManager = BiometricManager.from(this)
            if (biometricManager.canAuthenticate(allowedAuthenticators) ==
                BiometricManager.BIOMETRIC_SUCCESS
            ) {
                showBiometricPrompt()
            } else {
                tryKeyguardFallback()
            }
        }
    }

    /** ★ BUG 22: em Android 7–10 sem biometria, usa o bloqueio de tela do sistema. */
    private fun tryKeyguardFallback() {
        val km = getSystemService(KEYGUARD_SERVICE) as KeyguardManager
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R && km.isDeviceSecure) {
            @Suppress("DEPRECATION")
            val intent = km.createConfirmDeviceCredentialIntent(
                getString(R.string.login_biometria_titulo),
                getString(R.string.login_biometria_subtitulo)
            )
            if (intent != null) {
                keyguardLauncher.launch(intent)
                return
            }
        }
        Toast.makeText(
            this, getString(R.string.login_biometria_indisponivel), Toast.LENGTH_LONG
        ).show()
    }

    private fun showBiometricPrompt() {
        val executor = ContextCompat.getMainExecutor(this)
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                super.onAuthenticationSucceeded(result)
                togglePasswordVisibility()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                super.onAuthenticationError(errorCode, errString)
                val silentErrors = setOf(
                    BiometricPrompt.ERROR_USER_CANCELED,
                    BiometricPrompt.ERROR_NEGATIVE_BUTTON,
                    BiometricPrompt.ERROR_CANCELED
                )
                if (errorCode !in silentErrors) {
                    Toast.makeText(
                        this@LoginActivity,
                        getString(R.string.login_biometria_erro, errString),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }

        val biometricPrompt = BiometricPrompt(this, executor, callback)
        val builder = BiometricPrompt.PromptInfo.Builder()
            .setTitle(getString(R.string.login_biometria_titulo))
            .setSubtitle(getString(R.string.login_biometria_subtitulo))
            .setAllowedAuthenticators(allowedAuthenticators)

        // Sem DEVICE_CREDENTIAL (API < 30) o botão negativo é obrigatório
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            builder.setNegativeButtonText(getString(R.string.cancelar))
        }

        biometricPrompt.authenticate(builder.build())
    }

    private fun togglePasswordVisibility() {
        isPasswordVisible = !isPasswordVisible
        val savedText = etPass.text?.toString() ?: ""
        val cursorPos = etPass.selectionEnd.coerceAtLeast(0)
        etPass.transformationMethod =
            if (isPasswordVisible) null else PasswordTransformationMethod.getInstance()
        etPass.setText(savedText)
        etPass.setSelection(cursorPos.coerceAtMost(savedText.length))
    }
}