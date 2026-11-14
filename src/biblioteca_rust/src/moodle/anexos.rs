//! Download de anexos (arquivos) de tarefas do Moodle.
//!
//! Fluxo oficial: a URL (`fileurl`) devolvida pelos web services aponta para
//! `/webservice/pluginfile.php/...` e o download exige `?token=<wstoken>`.

use std::collections::hash_map::DefaultHasher;
use std::fs::{self, File};
use std::hash::{Hash, Hasher};
use std::io::BufWriter;
use std::path::Path;

use reqwest::header::CONTENT_TYPE;

use crate::error::CoreError;
use crate::http::download_client;
use crate::models::DownloadAnexoResult;
use crate::moodle::login::get_moodle_token;

const MOODLE_HOST: &str = "moodle.fei.edu.br";

/// Valida a URL (https + host do Moodle + pluginfile) e devolve uma URL com o
/// token anexado. O host é conferido para NUNCA enviar o token a terceiros.
fn montar_url_com_token(raw: &str, token: &str) -> Result<url::Url, CoreError> {
    let mut u = url::Url::parse(raw).map_err(|_| CoreError::InvalidInput)?;

    if u.scheme() != "https" || u.host_str() != Some(MOODLE_HOST) {
        return Err(CoreError::InvalidInput);
    }

    let path = u.path().to_string();
    if !path.contains("/pluginfile.php/") {
        return Err(CoreError::InvalidInput);
    }

    // Download via web service exige o prefixo /webservice/.
    if !path.contains("/webservice/pluginfile.php/") {
        u.set_path(&path.replacen("/pluginfile.php/", "/webservice/pluginfile.php/", 1));
    }

    // Substitui qualquer token antigo.
    let kept: Vec<(String, String)> = u
        .query_pairs()
        .filter(|(k, _)| k != "token")
        .map(|(k, v)| (k.into_owned(), v.into_owned()))
        .collect();
    u.set_query(None);
    {
        let mut q = u.query_pairs_mut();
        q.extend_pairs(kept);
        q.append_pair("token", token);
    }

    Ok(u)
}

/// Nome de arquivo seguro (sem separadores de caminho nem caracteres de controle).
fn nome_seguro(nome: &str) -> String {
    let limpo: String = nome
        .chars()
        .map(|c| match c {
            '/' | '\\' | ':' | '*' | '?' | '"' | '<' | '>' | '|' => '_',
            c if c.is_control() => '_',
            c => c,
        })
        .collect();
    let limpo = limpo.trim().trim_matches('.').to_string();
    if limpo.is_empty() {
        "anexo".to_string()
    } else {
        limpo.chars().take(120).collect()
    }
}

/// Pasta estável por arquivo: evita que dois anexos com o mesmo nome (de
/// tarefas diferentes) se sobrescrevam.
fn subpasta_por_url(url_sem_token: &str) -> String {
    let mut h = DefaultHasher::new();
    url_sem_token.hash(&mut h);
    format!("{:016x}", h.finish())
}

pub fn download_anexo_moodle(
    user: &str,
    pass: &str,
    url: &str,
    nome: &str,
    out_dir: &str,
) -> Result<DownloadAnexoResult, CoreError> {
    if user.is_empty() || pass.is_empty() || url.trim().is_empty() || out_dir.trim().is_empty() {
        return Err(CoreError::InvalidInput);
    }

    let token_data = get_moodle_token(user, pass)?;
    if !token_data.success {
        return Err(CoreError::SessionExpired(token_data.error_message));
    }
    let token = token_data
        .token
        .ok_or_else(|| CoreError::SessionExpired("Token do Moodle indisponível".into()))?;

    let final_url = montar_url_com_token(url, &token)?;

    let client = download_client();
    let mut resp = client.get(final_url).send()?;

    if !resp.status().is_success() {
        return Err(CoreError::Network(format!(
            "Falha ao baixar anexo (HTTP {})",
            resp.status().as_u16()
        )));
    }

    let content_type = resp
        .headers()
        .get(CONTENT_TYPE)
        .and_then(|v| v.to_str().ok())
        .unwrap_or_default()
        .split(';')
        .next()
        .unwrap_or_default()
        .trim()
        .to_lowercase();

    // Em erro de token/permissão o Moodle responde 200 com JSON em vez do arquivo.
    // Só inspecionamos o corpo quando o Content-Type indica JSON.
    if content_type.contains("json") {
        let body = resp.text()?;
        let code = serde_json::from_str::<serde_json::Value>(&body)
            .ok()
            .and_then(|v| v.get("errorcode").and_then(|c| c.as_str()).map(|s| s.to_string()));
        return Err(match code.as_deref() {
            Some("invalidtoken") | Some("accessexception") => {
                CoreError::SessionExpired("Token Moodle inválido ou acesso negado".into())
            }
            _ => CoreError::Parse("O Moodle recusou o download do anexo".into()),
        });
    }

    let url_base = {
        let mut u = url::Url::parse(url).map_err(|_| CoreError::InvalidInput)?;
        u.set_query(None);
        u.to_string()
    };

    let dir = Path::new(out_dir).join(subpasta_por_url(&url_base));
    fs::create_dir_all(&dir)?;

    let path = dir.join(nome_seguro(nome));
    let tmp = dir.join(".download.part");

    let size = {
        let file = File::create(&tmp)?;
        let mut writer = BufWriter::new(file);
        resp.copy_to(&mut writer)?
    };

    if size == 0 {
        let _ = fs::remove_file(&tmp);
        return Err(CoreError::Parse("Arquivo baixado está vazio".into()));
    }

    fs::rename(&tmp, &path)?;

    Ok(DownloadAnexoResult {
        path: path.to_string_lossy().to_string(),
        size,
        mime_type: content_type,
    })
}

#[cfg(test)]
mod testes {
    use super::*;

    #[test]
    fn url_recebe_token_e_prefixo_webservice() {
        let u = montar_url_com_token(
            "https://moodle.fei.edu.br/pluginfile.php/10/mod_assign/introattachment/0/a.pdf?forcedownload=1",
            "TKN",
        )
        .unwrap();
        assert_eq!(
            u.as_str(),
            "https://moodle.fei.edu.br/webservice/pluginfile.php/10/mod_assign/introattachment/0/a.pdf?forcedownload=1&token=TKN"
        );
    }

    #[test]
    fn url_ja_webservice_substitui_token_antigo() {
        let u = montar_url_com_token(
            "https://moodle.fei.edu.br/webservice/pluginfile.php/1/x.pdf?token=VELHO",
            "NOVO",
        )
        .unwrap();
        assert_eq!(u.as_str(), "https://moodle.fei.edu.br/webservice/pluginfile.php/1/x.pdf?token=NOVO");
    }

    #[test]
    fn rejeita_host_externo_e_http() {
        assert!(montar_url_com_token("https://evil.com/webservice/pluginfile.php/1/x.pdf", "T").is_err());
        assert!(montar_url_com_token("http://moodle.fei.edu.br/webservice/pluginfile.php/1/x.pdf", "T").is_err());
        assert!(montar_url_com_token("https://moodle.fei.edu.br/outra/coisa.pdf", "T").is_err());
    }

    #[test]
    fn nome_e_sanitizado() {
        assert_eq!(nome_seguro("../../etc/passwd"), "_.._etc_passwd");
        assert_eq!(nome_seguro("Lista 1: v2?.pdf"), "Lista 1_ v2_.pdf");
        assert_eq!(nome_seguro("..."), "anexo");
    }
}