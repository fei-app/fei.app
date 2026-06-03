package com.marinov.openfei

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.text.method.PasswordTransformationMethod
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.lifecycle.lifecycleScope
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LoginActivity : AppCompatActivity() {

    private lateinit var etUser: TextInputEditText
    private lateinit var etPass: TextInputEditText
    private lateinit var tilUser: TextInputLayout
    private lateinit var tilPass: TextInputLayout
    private lateinit var btnLogin: MaterialButton
    private lateinit var progressIndicator: CircularProgressIndicator

    private var isPasswordVisible = false

    companion object {
        const val PREFS_LOGIN = "login_prefs"
        const val KEY_USER = "saved_user"
        const val KEY_PASS = "saved_pass"
        const val KEY_IS_LOGGED_IN = "is_logged_in"

        fun getEncryptedPrefs(context: Context): SharedPreferences {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            return EncryptedSharedPreferences.create(
                context,
                PREFS_LOGIN,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }
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

        loadSavedCredentials()
        setupPasswordToggle()

        btnLogin.setOnClickListener {
            initiateLoginFlow()
        }

        // --- AUTO LOGIN: se já existem credenciais salvas, executa o login automaticamente ---
        val encryptedPrefs = getEncryptedPrefs(this)
        val savedUser = encryptedPrefs.getString(KEY_USER, "") ?: ""
        val savedPass = encryptedPrefs.getString(KEY_PASS, "") ?: ""
        if (savedUser.isNotEmpty() && savedPass.isNotEmpty()) {
            initiateLoginFlow()
        }
    }

    private fun setupPasswordToggle() {
        tilPass.setEndIconOnClickListener {
            if (isPasswordVisible) {
                // Senha já visível → esconde diretamente, sem autenticação
                togglePasswordVisibility()
                return@setEndIconOnClickListener
            }

            // Senha oculta → exige autenticação antes de revelar
            val biometricManager = BiometricManager.from(this)
            val canAuthenticate = biometricManager.canAuthenticate(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)

            if (canAuthenticate == BiometricManager.BIOMETRIC_SUCCESS) {
                showBiometricPrompt()
            } else {
                Toast.makeText(
                    this,
                    "Configure um bloqueio de tela para visualizar a senha",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
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
                        "Erro de autenticação: $errString",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }

        }

        val biometricPrompt = BiometricPrompt(this, executor, callback)

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Verificar identidade")
            .setSubtitle("Autentique-se para visualizar a senha")
            .setAllowedAuthenticators(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)
            .build()

        biometricPrompt.authenticate(promptInfo)
    }

    private fun togglePasswordVisibility() {
        isPasswordVisible = !isPasswordVisible

        // Salva o texto e posição do cursor antes de qualquer modificação
        val savedText = etPass.text?.toString() ?: ""
        val cursorPos = etPass.selectionEnd.coerceAtLeast(0)

        // Troca a transformação (oculto ↔ visível)
        etPass.transformationMethod =
            if (isPasswordVisible) null
            else PasswordTransformationMethod.getInstance()

        // Reaplicar o texto dispara o TextWatcher interno do TextInputLayout
        // (PasswordToggleEndIconDelegate), que atualiza o ícone do olho
        // automaticamente sem precisar de setEndIconChecked ou drawable manual.
        etPass.setText(savedText)
        etPass.setSelection(cursorPos.coerceAtMost(savedText.length))
    }

    // -------------------------------------------------------------------------
    // Restante da lógica original sem alterações
    // -------------------------------------------------------------------------

    private fun loadSavedCredentials() {
        val prefs = getEncryptedPrefs(this)
        val savedUser = prefs.getString(KEY_USER, "")
        val savedPass = prefs.getString(KEY_PASS, "")

        if (!savedUser.isNullOrEmpty() && !savedPass.isNullOrEmpty()) {
            etUser.setText(savedUser)
            etPass.setText(savedPass)
        }
    }

    private fun initiateLoginFlow() {
        val user = etUser.text.toString().trim()
        val pass = etPass.text.toString().trim()

        tilUser.error = null
        tilPass.error = null

        if (user.isEmpty()) {
            tilUser.error = "Preencha o usuário"
            return
        }
        if (pass.isEmpty()) {
            tilPass.error = "Preencha a senha"
            return
        }

        setLoading(true)

        lifecycleScope.launch {
            val result = LoginLogic.performLogin(user, pass, this@LoginActivity)

            withContext(Dispatchers.Main) {
                if (result.success) {
                    saveCredentialsAndLoginState(user, pass)
                    startMainActivity()
                } else {
                    setLoading(false)
                    Toast.makeText(this@LoginActivity, result.errorMessage, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun saveCredentialsAndLoginState(user: String, pass: String) {
        val prefs = getEncryptedPrefs(this)
        prefs.edit {
            putBoolean(KEY_IS_LOGGED_IN, true)
            putString(KEY_USER, user)
            putString(KEY_PASS, pass)
        }
    }

    private fun startMainActivity() {
        val intent = Intent(this, MainActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }

    private fun setLoading(isLoading: Boolean) {
        if (isLoading) {
            btnLogin.text = ""
            btnLogin.isEnabled = false
            progressIndicator.visibility = View.VISIBLE
        } else {
            btnLogin.text = "Entrar"
            btnLogin.isEnabled = true
            progressIndicator.visibility = View.GONE
        }
    }
}