package com.marinov.openfei.cache

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import com.marinov.openfei.login.CredentialsStore

/**
 * ★ BUG 80 — Proteção da PRIMEIRA execução dos serviços em segundo plano.
 *
 * Antes, "é a primeira vez?" era decidido com `cache.isEmpty()`, que é ambíguo:
 *   - cache vazio porque NUNCA houve sync (instalação limpa);
 *   - cache vazio porque a leitura falhou / os dados foram apagados;
 *   - cache preenchido pela UI (HomeFragment) segundos antes do 1º sync.
 *
 * No último caso o worker encontrava um "baseline" que ele nunca confirmou
 * (aulas com nome não resolvido, boletos parciais, etc.) e disparava
 * notificação de "novo horário" / "boletos atualizados" comparando dados
 * novos com dados nulos/incompletos.
 *
 * Agora existe um marco EXPLÍCITO por tipo de dado, gravado SOMENTE pelo
 * pipeline de sincronização. Enquanto o marco não existe:
 *   → semeia o cache, marca o marco e NÃO notifica.
 *
 * O marco é invalidado automaticamente quando a conta (usuário salvo) muda,
 * evitando comparar dados de alunos diferentes. Nenhum dado pessoal é
 * gravado aqui: apenas um hash não reversível do usuário.
 */
object SyncBaseline {

    private const val TAG = "SyncBaseline"
    private const val PREFS_NAME = "sync_baseline_prefs"
    private const val KEY_OWNER = "baseline_owner"
    private const val PREFIX = "baseline_ok_"

    const val NOTAS = "notas"
    const val AULAS = "aulas"
    const val BOLETOS = "boletos"
    const val PROVAS_FEI = "provas_fei"
    const val EVENTOS_MOODLE = "eventos_moodle"

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Identificador da conta dona do baseline (não reversível). */
    private fun owner(context: Context): String {
        val user = runCatching { CredentialsStore.user(context) }.getOrDefault("")
        return if (user.isBlank()) ""
        else Integer.toHexString(user.trim().lowercase().hashCode())
    }

    /** true quando já existe um snapshot confirmado pelo sync para este tipo. */
    fun isEstablished(context: Context, tipo: String): Boolean {
        val p = prefs(context)
        val dono = p.getString(KEY_OWNER, null)
        val atual = owner(context)
        if (dono != atual) return false
        return p.getBoolean(PREFIX + tipo, false)
    }

    /** true na primeira execução (sem baseline) — nunca notificar nesse caso. */
    fun isFirstRun(context: Context, tipo: String): Boolean = !isEstablished(context, tipo)

    /**
     * Só deve ser chamado com dados VÁLIDOS e NÃO VAZIOS em mãos.
     * Se a conta mudou, as marcas antigas são descartadas antes.
     */
    fun markEstablished(context: Context, tipo: String) {
        val p = prefs(context)
        val dono = p.getString(KEY_OWNER, null)
        val atual = owner(context)
        val marcasAntigas = p.all.keys.filter { it.startsWith(PREFIX) }
        p.edit {
            if (dono != atual) marcasAntigas.forEach { remove(it) }
            putString(KEY_OWNER, atual)
            putBoolean(PREFIX + tipo, true)
        }
        Log.d(TAG, "Baseline estabelecido: $tipo")
    }

    fun reset(context: Context, tipo: String) {
        prefs(context).edit { remove(PREFIX + tipo) }
    }

    /** Usado no "Limpar dados" / logout: próximo sync volta a ser primeira execução. */
    fun resetAll(context: Context) {
        prefs(context).edit { clear() }
        Log.d(TAG, "Baselines apagados — próximo sync será tratado como primeira execução")
    }
}