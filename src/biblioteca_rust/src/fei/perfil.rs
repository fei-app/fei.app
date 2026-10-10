use scraper::{Element, ElementRef, Html, Selector};
use crate::error::CoreError;
use crate::http::{execute_get_follow, http_client};
use crate::models::Perfil;

const FEI_URL_PERFIL: &str =
    "https://interage.fei.org.br/secureserver/portal/graduacao/secretaria/dados-pessoais";

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

    for b in panel_body.select(&sel("b")) {
        let label = text(&b).to_lowercase();

        if let Some(small) = b.next_sibling_element() {
            if let Some(em) = small.select(&sel("em")).next() {
                let value = text(&em);
                match label.as_str() {
                    "nome" => nome = value,
                    "matrícula" | "matricula" => matricula = value,
                    "curso" => curso = value,
                    _ => {}
                }
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