package com.marinov.openfei.data

import android.util.Log
import kotlinx.coroutines.CancellationException

object PerfilRepository {

    private const val TAG = "PerfilRepository"
    private const val URL_PERFIL =
        "https://interage.fei.org.br/secureserver/portal/graduacao/secretaria/dados-pessoais"

    suspend fun retornaDadosUsuario(online: Boolean): Perfil {
        return if (online) {
            try {
                val perfil = fetchPerfilFromServer()

                if (perfilTemDados(perfil)) {
                    CacheHelper.savePerfilCache(perfil)
                    perfil
                } else {
                    CacheHelper.getCachedPerfil() ?: Perfil("", "", "", "")
                }
            } catch (e: SessionExpiredException) {
                throw e
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Erro ao buscar perfil online", e)
                CacheHelper.getCachedPerfil() ?: Perfil("", "", "", "")
            }
        } else {
            CacheHelper.getCachedPerfil() ?: Perfil("", "", "", "")
        }
    }

    /**
     * Busca o perfil online e retorna null se não conseguir.
     * Isso permite que a UI decida se mantém cache ou mostra estado vazio/offline.
     */
    suspend fun obterPerfilOnlineOrNull(): Perfil? {
        return try {
            val perfil = fetchPerfilFromServer()

            if (!perfilTemDados(perfil)) {
                Log.w(TAG, "Perfil online veio vazio. Mantendo cache atual.")
                return null
            }

            CacheHelper.savePerfilCache(perfil)
            perfil
        } catch (e: SessionExpiredException) {
            Log.w(TAG, "Sessão expirada ao buscar perfil", e)
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao buscar perfil online", e)
            null
        }
    }

    fun obterPerfilCache(): Perfil? {
        return CacheHelper.getCachedPerfil()
    }

    private fun perfilTemDados(perfil: Perfil): Boolean {
        return perfil.nome.isNotBlank() ||
                perfil.matricula.isNotBlank() ||
                perfil.curso.isNotBlank() ||
                perfil.email.isNotBlank()
    }

    private suspend fun fetchPerfilFromServer(): Perfil {
        val doc = SessionManager.fetchPage(URL_PERFIL)

        val panelBody = doc.selectFirst(
            "body > div.container > div:nth-child(2) > div.col-md-9 > div.panel.panel-default.hidden-xs.bloco-conteudo-cabecalho > div.panel-body"
        ) ?: throw SessionExpiredException("Painel de perfil não encontrado")

        var nome = ""
        var matricula = ""
        var curso = ""

        panelBody.children().forEach { col ->
            val b = col.selectFirst("b")?.text()?.trim() ?: ""
            val em = col.selectFirst("small em")?.text()?.trim() ?: ""

            when {
                b.equals("Nome", ignoreCase = true) -> nome = em
                b.equals("Matrícula", ignoreCase = true) -> matricula = em
                b.equals("Curso", ignoreCase = true) -> curso = em
            }
        }

        val emailGroup = doc.selectFirst("#form-atualizar-dados-pessoais > div:nth-child(19)")
        val emailElement = emailGroup?.selectFirst("p.form-control-static")
        val email = emailElement?.text()?.trim() ?: ""

        return Perfil(nome, matricula, curso, email)
    }
}