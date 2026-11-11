use scraper::{Html, Selector};
use reqwest::header::REFERER;
use std::collections::HashMap;

use crate::error::CoreError;
use crate::http::{execute_get_follow, moodle_client, reset_moodle_client, collect_set_cookies, resolve_url};
use crate::models::MoodleTokenData;

const MOODLE_DOMAIN: &str = "moodle.fei.edu.br";
const MOODLE_LOGIN_URL: &str = "https://moodle.fei.edu.br/login/index.php";

fn sel(selector: &str) -> Selector {
    Selector::parse(selector).expect("Seletor CSS inválido")
}

fn input_value(doc: &Html, selector: &str) -> String {
    doc.select(&sel(selector))
        .next()
        .and_then(|i| i.value().attr("value").map(|v| v.trim().to_string()))
        .unwrap_or_default()
}

fn moodle_url_encode(s: &str) -> String {
    url::form_urlencoded::byte_serialize(s.as_bytes()).collect()
}

fn try_moodle_token(
    client: &reqwest::blocking::Client,
    user: &str,
    pass: &str,
) -> Result<Option<String>, CoreError> {
    let url = format!(
        "https://{}/login/token.php?username={}&password={}&service=moodle_mobile_app",
        MOODLE_DOMAIN,
        moodle_url_encode(user),
        moodle_url_encode(pass)
    );
    let resp = client.get(&url).send()?;
    let text = resp.text()?;
    let json: serde_json::Value = serde_json::from_str(&text).unwrap_or_default();
    if let Some(token) = json.get("token").and_then(|t| t.as_str()) {
        return Ok(Some(token.to_string()));
    }
    Ok(None)
}

/// Login COMPLETO no Moodle via formulário, sempre com jar limpo, para produzir
/// cookies de sessão de navegador realmente novos (consumidos pelo WebView).
pub fn moodle_login(user: &str, pass: &str) -> Result<MoodleTokenData, CoreError> {
    // ★ CORREÇÃO (bugs 3/18 + loop do Moodle):
    // 1) jar exclusivo do Moodle — não derruba mais a sessão da FEI;
    // 2) jar limpo a cada chamada — garante que o POST de login realmente aconteça
    //    e que os Set-Cookie retornados sejam uma sessão NOVA (antes, com o cliente
    //    já autenticado, o GET caía direto em /my/ e voltávamos "sucesso" sem
    //    nenhum cookie novo, deixando o WebView preso no /login/).
    reset_moodle_client();

    let client = moodle_client();
    let mut cookies = Vec::new();

    let (resp_get, final_url) = execute_get_follow(&client, MOODLE_LOGIN_URL, &mut cookies)?;
    let html_get = resp_get.text()?;
    let doc_get = Html::parse_document(&html_get);

    let logintoken = input_value(&doc_get, "input[name=logintoken]");

    if logintoken.is_empty() {
        // Sem formulário: ou já estamos autenticados (SSO/portal), ou o Moodle mudou.
        let authenticated = !final_url.contains("/login/");
        let token = try_moodle_token(&client, user, pass).ok().flatten();
        return Ok(MoodleTokenData {
            success: authenticated,
            error_message: if authenticated {
                String::new()
            } else {
                "logintoken não encontrado".to_string()
            },
            is_network_error: !authenticated,
            token,
            cookies,
        });
    }

    let form: HashMap<String, String> = [
        ("anchor".to_string(), "".to_string()),
        ("logintoken".to_string(), logintoken),
        ("username".to_string(), user.to_string()),
        ("password".to_string(), pass.to_string()),
    ]
    .into_iter()
    .collect();

    let mut resp = client
        .post(MOODLE_LOGIN_URL)
        .header(REFERER, MOODLE_LOGIN_URL)
        .form(&form)
        .send()?;

    collect_set_cookies(&resp, MOODLE_LOGIN_URL, &mut cookies);

    // ★ CORREÇÃO: antes, "success = status.is_redirection()" considerava sucesso
    // até o redirect de VOLTA para /login/index.php?loginerror=1 (senha errada).
    // Agora seguimos os redirects e verificamos a página final.
    let mut current_url = MOODLE_LOGIN_URL.to_string();
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
            .header(REFERER, MOODLE_LOGIN_URL)
            .send()?;
        collect_set_cookies(&resp, &current_url, &mut cookies);
    }

    let html_final = resp.text()?;
    let doc_final = Html::parse_document(&html_final);
    let still_on_login_page = current_url.contains("/login/")
        || doc_final.select(&sel("form#login")).next().is_some();

    if still_on_login_page {
        return Ok(MoodleTokenData {
            success: false,
            error_message: "Credenciais inválidas".to_string(),
            is_network_error: false,
            token: None,
            cookies,
        });
    }

    let token = try_moodle_token(&client, user, pass).ok().flatten();

    Ok(MoodleTokenData {
        success: true,
        error_message: String::new(),
        is_network_error: false,
        token,
        cookies,
    })
}

/// Token de API (stateless). Não garante cookies de sessão novos.
pub fn get_moodle_token(user: &str, pass: &str) -> Result<MoodleTokenData, CoreError> {
    let client = moodle_client();

    if let Some(token) = try_moodle_token(&client, user, pass)? {
        return Ok(MoodleTokenData {
            success: true,
            error_message: String::new(),
            is_network_error: false,
            token: Some(token),
            cookies: Vec::new(),
        });
    }

    moodle_login(user, pass)
}