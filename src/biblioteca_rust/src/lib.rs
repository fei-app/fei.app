mod error;
mod http;
mod models;
mod network;
mod fei;
mod moodle;

use std::ffi::{CStr, CString};
use std::os::raw::c_char;

use error::CoreError;
use serde::Serialize;

// ---------------------------------------------------------------------
// Envelopes JSON
// ---------------------------------------------------------------------

#[derive(Serialize)]
struct OkEnvelope<T: Serialize> {
    ok: bool,
    data: T,
}

#[derive(Serialize)]
struct ErrEnvelope {
    ok: bool,
    error: String,
    error_code: String,
}

fn ok_json<T: Serialize>(data: T) -> String {
    serde_json::to_string(&OkEnvelope { ok: true, data }).unwrap_or_else(|_| "{}".to_string())
}

fn error_json(e: CoreError) -> String {
    let code = match &e {
        CoreError::Network(_) => "NETWORK",
        CoreError::SessionExpired(_) => "SESSION_EXPIRED",
        CoreError::Parse(_) => "PARSE",
        CoreError::Io(_) => "IO",
        CoreError::InvalidInput => "INVALID_INPUT",
    };

    serde_json::to_string(&ErrEnvelope {
        ok: false,
        error: e.to_string(),
                          error_code: code.to_string(),
    })
    .unwrap_or_else(|_| "{}".to_string())
}

fn into_raw_json(json: String) -> *mut c_char {
    let safe = json.replace('\0', "");
    CString::new(safe)
    .unwrap_or_else(|_| CString::new("").unwrap())
    .into_raw()
}

unsafe fn ptr_to_string(ptr: *const c_char) -> String {
    if ptr.is_null() {
        return String::new();
    }

    CStr::from_ptr(ptr).to_string_lossy().into_owned()
}

// ---------------------------------------------------------------------
// API C / FFI
// ---------------------------------------------------------------------

#[no_mangle]
pub extern "C" fn openfei_free_string(ptr: *mut c_char) {
    if !ptr.is_null() {
        unsafe {
            drop(CString::from_raw(ptr));
        }
    }
}

#[no_mangle]
pub extern "C" fn openfei_clear_session() {
    http::reset_client();
}

#[no_mangle]
pub extern "C" fn openfei_is_online() -> *mut c_char {
    into_raw_json(ok_json(network::is_online()))
}

#[no_mangle]
pub extern "C" fn openfei_login(user: *const c_char, pass: *const c_char) -> *mut c_char {
    let user = unsafe { ptr_to_string(user) };
    let pass = unsafe { ptr_to_string(pass) };

    let result = fei::login_fei(&user, &pass);

    match result {
        Ok(data) => into_raw_json(ok_json(data)),
        Err(e) => into_raw_json(ok_json(models::LoginData {
            success: false,
            error_message: e.to_string(),
                                        is_network_error: matches!(e, CoreError::Network(_)),
                                        cookies: Vec::new(),
        })),
    }
}

// ★ CORREÇÃO: antes, `openfei_moodle_login` e `openfei_moodle_token`
// chamavam a MESMA função Rust (get_moodle_token), que dá preferência ao
// atalho da API de token (login/token.php) — atalho que NÃO produz cookies
// de sessão do navegador. Como o WebViewFragment usa `openfei_moodle_login`
// (via SessionManager.forcarRenovacaoCookiesMoodle) justamente para forçar
// cookies novos, isso fazia a "renovação forçada" retornar sucesso sem
// nenhum cookie novo, deixando o CookieManager do Android preso na sessão
// antiga e causando o loop /login/ -> /my/ -> /login/. Agora cada função C
// chama a rotina Rust correta e elas não se misturam mais.

#[no_mangle]
pub extern "C" fn openfei_moodle_login(user: *const c_char, pass: *const c_char) -> *mut c_char {
    let user = unsafe { ptr_to_string(user) };
    let pass = unsafe { ptr_to_string(pass) };

    match moodle::moodle_login(&user, &pass) {
        Ok(data) => into_raw_json(ok_json(data)),
        Err(e) => into_raw_json(ok_json(models::MoodleTokenData {
            success: false,
            error_message: e.to_string(),
                                        is_network_error: matches!(e, CoreError::Network(_)),
                                        token: None,
                                        cookies: Vec::new(),
        })),
    }
}

#[no_mangle]
pub extern "C" fn openfei_moodle_token(user: *const c_char, pass: *const c_char) -> *mut c_char {
    let user = unsafe { ptr_to_string(user) };
    let pass = unsafe { ptr_to_string(pass) };

    match moodle::get_moodle_token(&user, &pass) {
        Ok(data) => into_raw_json(ok_json(data)),
        Err(e) => into_raw_json(ok_json(models::MoodleTokenData {
            success: false,
            error_message: e.to_string(),
                                        is_network_error: matches!(e, CoreError::Network(_)),
                                        token: None,
                                        cookies: Vec::new(),
        })),
    }
}

#[no_mangle]
pub extern "C" fn openfei_fetch_disciplinas() -> *mut c_char {
    match fei::fetch_disciplinas() {
        Ok(data) => into_raw_json(ok_json(data)),
        Err(e) => into_raw_json(error_json(e)),
    }
}

#[no_mangle]
pub extern "C" fn openfei_fetch_notas() -> *mut c_char {
    match fei::fetch_notas() {
        Ok(data) => into_raw_json(ok_json(data)),
        Err(e) => into_raw_json(error_json(e)),
    }
}

#[no_mangle]
pub extern "C" fn openfei_fetch_medias() -> *mut c_char {
    match fei::fetch_medias() {
        Ok(data) => into_raw_json(ok_json(data)),
        Err(e) => into_raw_json(error_json(e)),
    }
}

#[no_mangle]
pub extern "C" fn openfei_fetch_aulas() -> *mut c_char {
    match fei::fetch_aulas() {
        Ok(data) => into_raw_json(ok_json(data)),
        Err(e) => into_raw_json(error_json(e)),
    }
}

#[no_mangle]
pub extern "C" fn openfei_fetch_perfil() -> *mut c_char {
    match fei::fetch_perfil() {
        Ok(data) => into_raw_json(ok_json(data)),
        Err(e) => into_raw_json(error_json(e)),
    }
}

#[no_mangle]
pub extern "C" fn openfei_fetch_provas_fei() -> *mut c_char {
    match fei::fetch_provas_fei() {
        Ok(data) => into_raw_json(ok_json(data)),
        Err(e) => into_raw_json(error_json(e)),
    }
}

#[no_mangle]
pub extern "C" fn openfei_fetch_boletos() -> *mut c_char {
    match fei::fetch_boletos() {
        Ok(data) => into_raw_json(ok_json(data)),
        Err(e) => into_raw_json(error_json(e)),
    }
}

#[no_mangle]
pub extern "C" fn openfei_fetch_carousel() -> *mut c_char {
    match fei::fetch_carousel() {
        Ok(data) => into_raw_json(ok_json(data)),
        Err(e) => into_raw_json(error_json(e)),
    }
}

#[no_mangle]
pub extern "C" fn openfei_download_boleto(
    titulo_id: *const c_char,
    vencimento: *const c_char,
    out_dir: *const c_char,
) -> *mut c_char {
    let titulo_id = unsafe { ptr_to_string(titulo_id) };
    let vencimento = unsafe { ptr_to_string(vencimento) };
    let out_dir = unsafe { ptr_to_string(out_dir) };

    match fei::download_boleto(&titulo_id, &vencimento, &out_dir) {
        Ok(data) => into_raw_json(ok_json(data)),
        Err(e) => into_raw_json(error_json(e)),
    }
}

#[no_mangle]
pub extern "C" fn openfei_fetch_eventos_moodle(
    user: *const c_char,
    pass: *const c_char,
) -> *mut c_char {
    let user = unsafe { ptr_to_string(user) };
    let pass = unsafe { ptr_to_string(pass) };

    match moodle::fetch_eventos_moodle(&user, &pass) {
        Ok(data) => into_raw_json(ok_json(data)),
        Err(e) => into_raw_json(error_json(e)),
    }
}

#[no_mangle]
pub extern "C" fn openfei_fetch_eventos_moodle_with_token(token: *const c_char) -> *mut c_char {
    let token = unsafe { ptr_to_string(token) };

    match moodle::fetch_eventos_moodle_with_token(&token) {
        Ok(data) => into_raw_json(ok_json(data)),
        Err(e) => into_raw_json(error_json(e)),
    }
}
