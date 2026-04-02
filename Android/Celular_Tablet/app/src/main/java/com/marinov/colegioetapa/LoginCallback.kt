package com.marinov.colegioetapa

/**
 * Interface para comunicação de eventos de login entre fragments
 */
interface LoginCallback {
    /**
     * Chamado quando o login é realizado com sucesso
     * @param shouldNavigateToHome Se true, navega para a home após o login
     */
    fun onLoginSuccess(shouldNavigateToHome: Boolean = true)

    /**
     * Chamado quando o logout é detectado
     */
    fun onLogout()

    /**
     * Navega diretamente para a home sem animação de login
     */
    fun navigateToHome()
}