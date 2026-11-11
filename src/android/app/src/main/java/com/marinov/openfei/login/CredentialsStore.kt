package com.marinov.openfei.login

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.KeyStore

/**
 * ★ BUGS 6, 7 e 58.
 *
 * - Era criado um EncryptedSharedPreferences NOVO a cada chamada (Keystore + Tink
 *   a cada leitura) → agora é singleton em memória.
 * - Se o keyset corromper (restore de backup, troca de lockscreen, etc.) o create()
 *   lança e o app crashava no LoginActivity → agora há recuperação controlada.
 * - "Limpar senhas" usava SharedPreferences PLANO no arquivo criptografado,
 *   apagando os keysets do Tink → agora removemos só as nossas chaves.
 */
object CredentialsStore {

    private const val TAG = "CredentialsStore"

    const val PREFS_LOGIN = "login_prefs"
    const val KEY_USER = "saved_user"
    const val KEY_PASS = "saved_pass"
    const val KEY_IS_LOGGED_IN = "is_logged_in"

    private const val MASTER_KEY_ALIAS = "_androidx_security_master_key_"

    @Volatile
    private var cached: SharedPreferences? = null

    @Synchronized
    fun prefs(context: Context): SharedPreferences? {
        cached?.let { return it }
        val app = context.applicationContext

        val created = create(app) ?: run {
            Log.w(TAG, "Storage criptografado corrompido — recriando")
            recover(app)
            create(app)
        }

        if (created == null) {
            Log.e(TAG, "Não foi possível abrir/criar o storage criptografado")
        }
        cached = created
        return created
    }

    private fun create(app: Context): SharedPreferences? = try {
        val masterKey = MasterKey.Builder(app)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            app,
            PREFS_LOGIN,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        Log.e(TAG, "Erro ao abrir credenciais criptografadas", e)
        null
    }

    private fun recover(app: Context) {
        runCatching { app.deleteSharedPreferences(PREFS_LOGIN) }
        runCatching {
            KeyStore.getInstance("AndroidKeyStore").apply {
                load(null)
                if (containsAlias(MASTER_KEY_ALIAS)) deleteEntry(MASTER_KEY_ALIAS)
            }
        }
    }

    fun user(context: Context): String =
        prefs(context)?.getString(KEY_USER, "").orEmpty()

    fun pass(context: Context): String =
        prefs(context)?.getString(KEY_PASS, "").orEmpty()

    fun hasCredentials(context: Context): Boolean =
        user(context).isNotEmpty() && pass(context).isNotEmpty()

    fun isLoggedIn(context: Context): Boolean =
        prefs(context)?.getBoolean(KEY_IS_LOGGED_IN, false) ?: false

    fun setLoggedIn(context: Context, value: Boolean) {
        prefs(context)?.edit { putBoolean(KEY_IS_LOGGED_IN, value) }
    }

    fun saveCredentials(context: Context, user: String, pass: String) {
        prefs(context)?.edit {
            putBoolean(KEY_IS_LOGGED_IN, true)
            putString(KEY_USER, user)
            putString(KEY_PASS, pass)
        }
    }

    /**
     * Remove APENAS as chaves do app.
     * Nunca usar clear() aqui: o EncryptedSharedPreferences guarda os keysets do
     * Tink no mesmo arquivo, e clear() os apagaria, quebrando o storage.
     */
    fun clearCredentials(context: Context) {
        prefs(context)?.edit {
            remove(KEY_USER)
            remove(KEY_PASS)
            remove(KEY_IS_LOGGED_IN)
        }
    }

    /** Wipe total do arquivo (usado no "resetar app"). */
    @Synchronized
    fun wipe(context: Context) {
        val app = context.applicationContext
        runCatching { app.deleteSharedPreferences(PREFS_LOGIN) }
        cached = null
    }
}