use scraper::{Html, Selector};
use reqwest::header::REFERER;
use std::collections::HashMap;

use crate::error::CoreError;
use crate::http::{execute_get_follow, http_client, collect_set_cookies, resolve_url};
use crate::models::{CookieRecord, LoginData};

const FEI_LOGIN_URL: &str = "https://interage.fei.org.br/secureserver/portal";
const FEI_HOME_URL: &str = "https://interage.fei.org.br/secureserver/portal/graduacao/home";

fn sel(selector: &str) -> Selector {
    Selector::parse(selector).expect("Seletor CSS inválido")
}

fn input_value(doc: &Html, selector: &str) -> String {
    doc.select(&sel(selector))
    .next()
    .and_then(|i| i.value().attr("value").map(|v| v.trim().to_string()))
    .unwrap_or_default()
}

fn login_error_message(doc: &Html) -> String {
    let err_sel = sel(".field-validation-error");
    doc.select(&err_sel)
    .map(|e| e.text().collect::<String>().trim().to_string())
    .collect::<Vec<_>>()
    .join(" ")
    .trim()
    .to_string()
}

pub fn login_fei(user: &str, pass: &str) -> Result<LoginData, CoreError> {
    let client = http_client();
    let mut cookies = Vec::new();

    // GET página de login
    let (resp_get, _) = execute_get_follow(&client, FEI_LOGIN_URL, &mut cookies)?;
    let html_get = resp_get.text()?;
    let doc_get = Html::parse_document(&html_get);

    let token = input_value(&doc_get, "input[name=__RequestVerificationToken]");
    if token.is_empty() {
        return Ok(LoginData {
            success: false,
            error_message: "Token de login não encontrado".to_string(),
                  is_network_error: true,
                  cookies,
        });
    }

    // POST credenciais
    let form: HashMap<String, String> = [
        ("__RequestVerificationToken".to_string(), token),
        ("Usuario".to_string(), user.to_string()),
        ("Senha".to_string(), pass.to_string()),
    ]
    .into_iter()
    .collect();

    let mut resp = client
    .post(format!("{}/", FEI_LOGIN_URL))
    .header(REFERER, FEI_LOGIN_URL)
    .form(&form)
    .send()?;

    collect_set_cookies(&resp, FEI_LOGIN_URL, &mut cookies);

    let mut current_url = FEI_LOGIN_URL.to_string();
    let mut reached_home = false;

    for _ in 0..10 {
        if !resp.status().is_redirection() {
            break;
        }

        let location = resp
        .headers()
        .get("Location")
        .and_then(|v| v.to_str().ok())
        .map(|s| s.to_string());

        let Some(loc) = location else { break };

        current_url = resolve_url(&current_url, &loc);

        resp = client
        .get(&current_url)
        .header(REFERER, FEI_LOGIN_URL)
        .send()?;

        collect_set_cookies(&resp, &current_url, &mut cookies);

        if current_url.starts_with(FEI_HOME_URL) {
            reached_home = true;
            break;
        }
    }

    if current_url.starts_with(FEI_HOME_URL) {
        reached_home = true;
    }

    let final_html = resp.text()?;
    let final_doc = Html::parse_document(&final_html);

    let success = reached_home && final_doc.select(&sel("#btn-login")).next().is_none();

    let error_message = if success {
        String::new()
    } else {
        let msg = login_error_message(&final_doc);
        if msg.is_empty() {
            "Credenciais inválidas".to_string()
        } else {
            msg
        }
    };

    Ok(LoginData {
        success,
       error_message,
       is_network_error: false,
       cookies,
    })
}
