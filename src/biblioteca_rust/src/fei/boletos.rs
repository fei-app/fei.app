use scraper::{ElementRef, Html, Selector};
use reqwest::header::{CONTENT_TYPE, REFERER};
use std::path::Path;
use std::fs;

use crate::error::CoreError;
use crate::http::{execute_get_follow, http_client};
use crate::models::{Boleto, DownloadBoletoResult};

const FEI_URL_BOLETOS: &str = "https://interage.fei.org.br/secureserver/portal/graduacao/tesouraria/consultas/boletos";
const FEI_URL_GERAR_BOLETO: &str = "https://interage.fei.org.br/secureserver/portal/graduacao/tesouraria/consultas/boletos/titulos/gerar";

fn sel(selector: &str) -> Selector {
    Selector::parse(selector).expect("Seletor CSS inválido")
}

fn text(el: &ElementRef) -> String {
    el.text().collect::<String>().trim().to_string()
}

fn cell_text_by_class_contains(row: &ElementRef, needle: &str) -> Option<String> {
    let td_sel = sel("td");
    let needle = needle.to_lowercase();

    row.select(&td_sel).find(|td| {
        td.value()
            .attr("class")
            .map(|c| c.to_lowercase().contains(&needle))
            .unwrap_or(false)
    }).map(|c| text(&c))
}

fn ensure_authenticated(doc: &Html) -> Result<(), CoreError> {
    if doc.select(&sel("#btn-login")).next().is_some() {
        return Err(CoreError::SessionExpired(
            "Página de login detectada — sessão inválida".to_string(),
        ));
    }
    Ok(())
}

fn boleto_file_name(vencimento: &str, titulo_id: &str) -> String {
    let partes: Vec<&str> = vencimento.split('/').collect();
    if partes.len() == 3 {
        format!("{}_{}.pdf", partes[2], partes[1])
    } else {
        format!("{}.pdf", titulo_id)
    }
}

pub fn fetch_boletos() -> Result<Vec<Boleto>, CoreError> {
    let client = http_client();
    let mut cookies = Vec::new();

    let (resp, _) = execute_get_follow(&client, FEI_URL_BOLETOS, &mut cookies)?;
    let html = resp.text()?;
    let doc = Html::parse_document(&html);

    ensure_authenticated(&doc)?;

    let form = doc
        .select(&sel("#form-gerar-boletos"))
        .next()
        .ok_or_else(|| {
            CoreError::SessionExpired("Formulário de boletos não encontrado — sessão inválida".into())
        })?;

    let tabela = form
        .select(&sel("table.table"))
        .next()
        .ok_or_else(|| CoreError::SessionExpired("Tabela de boletos não encontrada".into()))?;

    let mut boletos = Vec::new();

    for linha in tabela.select(&sel("tbody > tr")) {
        let vencimento = cell_text_by_class_contains(&linha, "vencimento").unwrap_or_default();
        let status = cell_text_by_class_contains(&linha, "status").unwrap_or_default();
        let data_pagamento = cell_text_by_class_contains(&linha, "data").unwrap_or_default();

        let titulo_id = linha
            .select(&sel("input[name=titulos]"))
            .next()
            .and_then(|i| i.value().attr("value").map(|v| v.trim().to_string()))
            .unwrap_or_default();

        if !vencimento.is_empty() && !status.is_empty() {
            boletos.push(Boleto {
                vencimento,
                status,
                data_pagamento,
                titulo_id,
            });
        }
    }

    Ok(boletos)
}

pub fn download_boleto(
    titulo_id: &str,
    vencimento: &str,
    out_dir: &str,
) -> Result<DownloadBoletoResult, CoreError> {
    let client = http_client();
    let mut cookies = Vec::new();

    let (resp, _) = execute_get_follow(&client, FEI_URL_BOLETOS, &mut cookies)?;
    let html = resp.text()?;
    let doc = Html::parse_document(&html);

    let csrf = doc
        .select(&sel("#form-gerar-boletos input[name=__RequestVerificationToken]"))
        .next()
        .and_then(|i| i.value().attr("value").map(|v| v.to_string()))
        .ok_or_else(|| CoreError::SessionExpired("CSRF token não encontrado na página de boletos".into()))?;

    let form: Vec<(String, String)> = vec![
        ("__RequestVerificationToken".to_string(), csrf),
        ("respFinanceiro".to_string(), "0".to_string()),
        ("titulos".to_string(), titulo_id.to_string()),
    ];

    let resp = client
        .post(FEI_URL_GERAR_BOLETO)
        .header(REFERER, FEI_URL_BOLETOS)
        .header("Accept", "application/pdf,text/html,*/*")
        .form(&form)
        .send()?;

    let content_type = resp
        .headers()
        .get(CONTENT_TYPE)
        .and_then(|v| v.to_str().ok())
        .unwrap_or_default()
        .to_lowercase();

    if !content_type.contains("pdf") {
        return Err(CoreError::Parse(format!(
            "Resposta não é PDF (Content-Type={})",
            content_type
        )));
    }

    let bytes = resp.bytes()?;
    if bytes.len() < 1000 {
        return Err(CoreError::Parse(format!(
            "PDF suspeito: apenas {} bytes",
            bytes.len()
        )));
    }

    let dir = Path::new(out_dir);
    fs::create_dir_all(dir)?;

    let file_name = boleto_file_name(vencimento, titulo_id);
    let path = dir.join(file_name);

    fs::write(&path, &bytes)?;

    Ok(DownloadBoletoResult {
        path: path.to_string_lossy().to_string(),
        size: bytes.len(),
    })
}
