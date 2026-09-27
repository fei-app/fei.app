use reqwest::blocking::{Client, Response};
use reqwest::header::{LOCATION, SET_COOKIE};
use reqwest::redirect::Policy;
use std::sync::{Mutex, OnceLock};
use std::time::Duration;
use url::Url;

use crate::error::CoreError;
use crate::models::CookieRecord;

const USER_AGENT: &str = "Mozilla/5.0 (Linux; Android 16; sdk_gphone64_x86_64 Build/BE2A.250530.026.D1; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/133.0.6943.137 Mobile Safari/537.36";

static CLIENT: OnceLock<Mutex<Client>> = OnceLock::new();

pub fn build_client() -> Client {
    Client::builder()
        .user_agent(USER_AGENT)
        .cookie_store(true)
        .redirect(Policy::none())
        .timeout(Duration::from_secs(30))
        .build()
        .expect("Falha ao criar cliente HTTP")
}

pub fn http_client() -> Client {
    CLIENT
        .get_or_init(|| Mutex::new(build_client()))
        .lock()
        .unwrap()
        .clone()
}

pub fn reset_client() {
    let mutex = CLIENT.get_or_init(|| Mutex::new(build_client()));
    *mutex.lock().unwrap() = build_client();
}

pub fn collect_set_cookies(resp: &Response, origin: &str, out: &mut Vec<CookieRecord>) {
    for value in resp.headers().get_all(SET_COOKIE).iter() {
        if let Ok(s) = value.to_str() {
            out.push(CookieRecord {
                origin: origin.to_string(),
                cookie_line: s.to_string(),
            });
        }
    }
}

pub fn resolve_url(base: &str, location: &str) -> String {
    if location.starts_with("http") {
        return location.to_string();
    }

    match Url::parse(base) {
        Ok(base_url) => match base_url.join(location) {
            Ok(u) => u.to_string(),
            Err(_) => location.to_string(),
        },
        Err(_) => location.to_string(),
    }
}

pub fn execute_get_follow(
    client: &Client,
    url: &str,
    cookies: &mut Vec<CookieRecord>,
) -> Result<(Response, String), CoreError> {
    let mut current = url.to_string();

    for _ in 0..10 {
        let resp = client.get(&current).send()?;
        collect_set_cookies(&resp, &current, cookies);

        if resp.status().is_redirection() {
            let location = resp
                .headers()
                .get(LOCATION)
                .and_then(|v| v.to_str().ok())
                .map(|s| s.to_string());

            if let Some(loc) = location {
                current = resolve_url(&current, &loc);
                continue;
            }
        }

        return Ok((resp, current));
    }

    Err(CoreError::Network("Muitos redirecionamentos".to_string()))
}
