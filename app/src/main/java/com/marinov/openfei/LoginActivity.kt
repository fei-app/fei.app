package com.marinov.openfei

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.lifecycle.lifecycleScope
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

    companion object {
        const val PREFS_LOGIN = "login_prefs"
        const val KEY_USER = "saved_user"
        const val KEY_PASS = "saved_pass"
        const val KEY_IS_LOGGED_IN = "is_logged_in"
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

        btnLogin.setOnClickListener {
            initiateLoginFlow()
        }
    }

    private fun loadSavedCredentials() {
        val prefs = getSharedPreferences(PREFS_LOGIN, MODE_PRIVATE)
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
        val prefs = getSharedPreferences(PREFS_LOGIN, MODE_PRIVATE)
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