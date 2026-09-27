use scraper::{ElementRef, Html, Selector};
use std::collections::HashMap;

use crate::error::CoreError;
use crate::fei::disciplinas::fetch_disciplinas;
use crate::http::{execute_get_follow, http_client};
use crate::models::ProvaCalendario;

const FEI_URL_PROVAS: &str = "https://interage.fei.org.br/secureserver/portal/graduacao/sala-dos-professores/informacoes-academicas/provas";

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

pub fn fetch_provas_fei() -> Result<Vec<ProvaCalendario>, CoreError> {
    let client = http_client();
    let mut cookies = Vec::new();

    let (resp, _) = execute_get_follow(&client, FEI_URL_PROVAS, &mut cookies)?;
    let html = resp.text()?;
    let doc = Html::parse_document(&html);

    ensure_authenticated(&doc)?;

    let disciplinas = fetch_disciplinas().unwrap_or_default();
    let mapa: HashMap<String, String> = disciplinas
        .into_iter()
        .map(|d| (d.codigo, d.nome))
        .collect();

    let accordion = doc
        .select(&sel("#accordion-provas"))
        .next()
        .ok_or_else(|| CoreError::SessionExpired("Accordion de provas não encontrado".into()))?;

    let mut provas = Vec::new();

    for panel in accordion.select(&sel("div.panel.panel-default")) {
        let title_el = panel.select(&sel(".panel-title a")).next();
        let Some(title_el) = title_el else { continue };

        let titulo = text(&title_el);

        let tipo_prova = if titulo.contains("(P1)") {
            "P1"
        } else if titulo.contains("(P2)") {
            "P2"
        } else if titulo.contains("(P3)") {
            "P3"
        } else {
            continue;
        }
        .to_string();

        let Some(tabela) = panel.select(&sel("div.panel-body table.table")).next() else {
            continue;
        };

        for row in tabela.select(&sel("tbody > tr")) {
            let codigo = cell_text_by_class_contains(&row, "disciplina").unwrap_or_default();
            let prova_texto = cell_text_by_class_contains(&row, "prova").unwrap_or_default();
            let hora = cell_text_by_class_contains(&row, "hora").unwrap_or_default();
            let sala = cell_text_by_class_contains(&row, "sala").unwrap_or_default();
            let coordenador = cell_text_by_class_contains(&row, "coordenador").unwrap_or_default();

            if codigo.is_empty() || prova_texto.is_empty() {
                continue;
            }

            let data_prova = prova_texto
                .split_whitespace()
                .next()
                .unwrap_or_default()
                .to_string();

            let nome = mapa
                .get(&codigo)
                .cloned()
                .unwrap_or_else(|| codigo.clone());

            provas.push(ProvaCalendario {
                disciplina: codigo,
                nome_disciplina: nome,
                data_prova,
                hora,
                sala: Some(sala),
                coordenador,
                tipo_prova: tipo_prova.clone(),
            });
        }
    }

    Ok(provas)
}
