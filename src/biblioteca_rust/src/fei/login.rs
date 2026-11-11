use scraper::{Html, Selector};
use reqwest::header::REFERER;
use std::collections::HashMap;

use crate::error::CoreError;
use crate::http::{execute_get_follow, fei_client, reset_fei_client, collect_set_cookies, resolve_url};
use crate::models::LoginData;

// no topo
use crate::fei::session::is_login_page;

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
    // ★ CORREÇÃO (bug 5): o cliente é compartilhado e guarda cookies. Se já havia
    // sessão de OUTRO usuário, o GET /portal redirecionava direto para a home,
    // não existia __RequestVerificationToken e o login retornava "Token não
    // encontrado" — ou pior, sucesso com a sessão do usuário anterior.
    // Sempre começamos de um jar limpo.
    reset_fei_client();

    let client = fei_client();
    let mut cookies = Vec::new();

    let (resp_get, _) = execute_get_follow(&client, FEI_LOGIN_URL, &mut cookies)?;
    let html_get = resp_get.text()?;
    let doc_get = Html::parse_document(&html_get);

    let token = input_value(&doc_get, "input[name=__RequestVerificationToken]");
    if token.is_empty() {
        return Ok(LoginData {
            success: false,
            error_message: "Token de login não encontrado".to_string(),
            // Página fora do ar / portal mudou: NÃO é credencial inválida.
            is_network_error: true,
            cookies,
        });
    }

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

    // ★ PROBLEMA 3: usa o detector robusto
    let success = reached_home && !is_login_page(&final_doc);

    let error_message = if success {
        String::new()
    } else {
        let msg = login_error_message(&final_doc);
        if msg.is_empty() { "Credenciais inválidas".to_string() } else { msg }
    };

    // ★ Se não chegou na home E não há formulário de login nem mensagem de
    // erro, é resposta inesperada → erro de REDE, não credencial inválida.
    let ambiguous = !success && !is_login_page(&final_doc) && login_error_message(&final_doc).is_empty();

    Ok(LoginData {
        success,
        error_message,
        is_network_error: ambiguous,
        cookies,
    })

}