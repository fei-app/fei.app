use scraper::{ElementRef, Html, Selector};

use crate::error::CoreError;
use crate::http::{execute_get_follow, http_client, collect_set_cookies};
use crate::models::{Perfil, CookieRecord};

const FEI_URL_PERFIL: &str = "https://interage.fei.org.br/secureserver/portal/graduacao/secretaria/dados-pessoais";

fn sel(selector: &str) -> Selector {
    Selector::parse(selector).expect("Seletor CSS inválido")
}

fn text(el: &ElementRef) -> String {
    el.text().collect::<String>().trim().to_string()
}

fn ensure_authenticated(doc: &Html) -> Result<(), CoreError> {
    if doc.select(&sel("#btn-login")).next().is_some() {
        return Err(CoreError::SessionExpired(
            "Página de login detectada — sessão inválida".to_string(),
        ));
    }
    Ok(())
}

pub fn fetch_perfil() -> Result<Perfil, CoreError> {
    let client = http_client();
    let mut cookies = Vec::new();

    let (resp, _) = execute_get_follow(&client, FEI_URL_PERFIL, &mut cookies)?;
    let html = resp.text()?;
    let doc = Html::parse_document(&html);

    ensure_authenticated(&doc)?;

    let panel_body = doc
        .select(&sel("div.panel-body"))
        .find(|el| {
            let t = el.text().collect::<String>();
            t.contains("Nome") && t.contains("Matrícula")
        })
        .ok_or_else(|| CoreError::SessionExpired("Painel de perfil não encontrado".into()))?;

    let mut nome = String::new();
    let mut matricula = String::new();
    let mut curso = String::new();

    for col in panel_body.select(&sel("div")) {
        let b = col.select(&sel("b")).next();
        let em = col.select(&sel("small em")).next();

        if let (Some(b), Some(em)) = (b, em) {
            let label = text(&b).to_lowercase();
            let value = text(&em);

            if label == "nome" {
                nome = value;
            } else if label == "matrícula" {
                matricula = value;
            } else if label == "curso" {
                curso = value;
            }
        }
    }

    let email = doc
        .select(&sel("p.form-control-static"))
        .find(|el| text(el).contains('@'))
        .map(|el| text(&el))
        .unwrap_or_default();

    Ok(Perfil {
        nome,
        matricula,
        curso,
        email,
    })
}
