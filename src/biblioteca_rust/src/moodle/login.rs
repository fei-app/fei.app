use scraper::{Html, Selector};
use reqwest::header::REFERER;
use std::collections::HashMap;

use crate::error::CoreError;
use crate::http::{execute_get_follow, http_client, collect_set_cookies};
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

fn try_moodle_token(client: &reqwest::blocking::Client, user: &str, pass: &str) -> Result<Option<String>, CoreError> {
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

/// ★ CORREÇÃO DO LOOP DE LOGIN: faz SEMPRE o login completo do Moodle via
/// formulário (username/senha), obtendo cookies de sessão FRESCOS do
/// "navegador" (usados pelo WebView). Diferente de `get_moodle_token`, esta
/// função NUNCA usa o atalho da API `login/token.php` — o token da API
/// mobile e o cookie de sessão do navegador são independentes (um pode
/// continuar válido mesmo com o outro expirado), então usar o atalho aqui
/// fazia esta função retornar sucesso sem nenhum cookie novo, mantendo o
/// CookieManager do Android com a sessão antiga/expirada e causando o loop
/// de redirecionamento /login/ -> /my/ -> /login/ no WebViewFragment.
///
/// Use esta função sempre que precisar renovar de fato a sessão do
/// navegador (ex.: `SessionManager.forcarRenovacaoCookiesMoodle`). Para
/// apenas obter um token de API válido (sem necessidade de cookies novos),
/// use `get_moodle_token`.
pub fn moodle_login(user: &str, pass: &str) -> Result<MoodleTokenData, CoreError> {
    let client = http_client();
    let mut cookies = Vec::new();

    let (resp_get, final_url) = execute_get_follow(&client, MOODLE_LOGIN_URL, &mut cookies)?;
    let html_get = resp_get.text()?;
    let doc_get = Html::parse_document(&html_get);

    // ★ CORREÇÃO: o cliente HTTP do Rust é compartilhado e mantém cookies
    // entre chamadas (cookie_store(true) em http.rs). Se esta função já foi
    // chamada com sucesso há pouco (ex.: uma segunda tentativa de
    // renovação do WebViewFragment), o cliente Rust já está autenticado, e
    // o Moodle nem chega a mostrar o formulário de /login/ — ele redireciona
    // direto para /my/. Nesse caso não existe "logintoken" pra encontrar, e
    // tratar isso como falha ("logintoken não encontrado") era um falso
    // negativo que atrapalhava a nova tentativa. Se o GET não terminou mais
    // em /login/, já estamos autenticados: os cookies coletados neste GET
    // já são a sessão válida.
    if !final_url.contains("/login/") {
        let token = try_moodle_token(&client, user, pass).ok().flatten();
        return Ok(MoodleTokenData {
            success: true,
            error_message: String::new(),
                  is_network_error: false,
                  token,
                  cookies,
        });
    }

    let logintoken = input_value(&doc_get, "input[name=logintoken]");
    if logintoken.is_empty() {
        return Ok(MoodleTokenData {
            success: false,
            error_message: "logintoken não encontrado".to_string(),
                  is_network_error: true,
                  token: None,
                  cookies,
        });
    }

    let form: HashMap<String, String> = [
        ("anchor".to_string(), "".to_string()),
        ("logintoken".to_string(), logintoken),
        ("username".to_string(), user.to_string()),
        ("password".to_string(), pass.to_string()),
    ].into_iter().collect();

    let resp_post = client
    .post(MOODLE_LOGIN_URL)
    .header(REFERER, MOODLE_LOGIN_URL)
    .form(&form)
    .send()?;

    collect_set_cookies(&resp_post, MOODLE_LOGIN_URL, &mut cookies);

    let success = if resp_post.status().is_redirection() {
        true
    } else {
        let html = resp_post.text()?;
        let doc = Html::parse_document(&html);
        doc.select(&sel("form#login")).next().is_none()
    };

    if !success {
        return Ok(MoodleTokenData {
            success: false,
            error_message: "Credenciais inválidas".to_string(),
                  is_network_error: false,
                  token: None,
                  cookies,
        });
    }

    // Login por formulário OK. Tenta obter também o token de API — é apenas
    // um bônus para quem chamou esta função; se falhar, os cookies de
    // sessão já obtidos continuam sendo o resultado importante.
    let token = try_moodle_token(&client, user, pass).ok().flatten();

    Ok(MoodleTokenData {
        success: true,
       error_message: String::new(),
       is_network_error: false,
       token,
       cookies,
    })
}

/// Obtém um token de API do Moodle válido. Tenta primeiro o atalho stateless
/// (`login/token.php`), que não depende de cookies de sessão; só recorre ao
/// login completo por formulário se o atalho falhar. Não garante cookies de
/// sessão novos quando o atalho funciona — para isso, use `moodle_login`.
pub fn get_moodle_token(user: &str, pass: &str) -> Result<MoodleTokenData, CoreError> {
    let client = http_client();

    if let Some(token) = try_moodle_token(&client, user, pass)? {
        return Ok(MoodleTokenData {
            success: true,
            error_message: String::new(),
                  is_network_error: false,
                  token: Some(token),
                  cookies: Vec::new(),
        });
    }

    // Token direto falhou — faz o login completo, que também garante
    // cookies de sessão válidos como efeito colateral.
    moodle_login(user, pass)
}
