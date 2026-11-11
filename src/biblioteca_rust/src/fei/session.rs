use scraper::{Html, Selector};
use crate::error::CoreError;

fn sel(s: &str) -> Selector {
    Selector::parse(s).expect("Seletor CSS inválido")
}

/// ★ PROBLEMA 3: uma página só é "página de login" se tiver o formulário
/// de credenciais. Antes, qualquer HTML sem o elemento esperado (502,
/// manutenção, portal cativo, resposta truncada por queda de sinal) era
/// classificado como SessionExpired — e isso deslogava o usuário.
pub fn is_login_page(doc: &Html) -> bool {
    let has_login_btn = doc.select(&sel("#btn-login")).next().is_some();
    let has_user_field = doc.select(&sel("input[name=Usuario]")).next().is_some();
    let has_pass_field = doc.select(&sel("input[name=Senha]")).next().is_some();
    has_login_btn || (has_user_field && has_pass_field)
}

pub fn ensure_authenticated(doc: &Html) -> Result<(), CoreError> {
    if is_login_page(doc) {
        return Err(CoreError::SessionExpired(
            "Formulário de login detectado — sessão realmente inválida".to_string(),
        ));
    }
    Ok(())
}

/// Elemento esperado ausente, mas SEM formulário de login:
/// é resposta inesperada do servidor, não sessão expirada.
pub fn unexpected_page(contexto: &str) -> CoreError {
    CoreError::Parse(format!(
        "{} — resposta inesperada do servidor (não é sessão expirada)",
        contexto
    ))
}