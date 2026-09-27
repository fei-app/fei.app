use scraper::{ElementRef, Html, Selector};
use std::collections::HashMap;

use crate::error::CoreError;
use crate::http::{execute_get_follow, http_client};
use crate::models::Nota;

const FEI_URL_NOTAS: &str = "https://interage.fei.org.br/secureserver/portal/graduacao/secretaria/consultas/notas";

fn sel(selector: &str) -> Selector {
    Selector::parse(selector).expect("Seletor CSS inválido")
}

// ★ CORREÇÃO: `scraper`/`html5ever` (Rust) NÃO normaliza espaços em branco
// como o Jsoup (Kotlin) fazia — ele apenas concatena os nós de texto crus,
// preservando quebras de linha/indentação do HTML original. A versão antiga
// em Kotlin usava `Element.text()` do Jsoup, que colapsa qualquer sequência
// de espaços/quebras de linha em um único espaço. Sem essa normalização
// aqui, uma célula como a da Média — cujo HTML costuma ter indentação ou
// elementos internos — podia sair com quebras de linha no meio do valor
// (ex.: "7,5\n") e quebrar o `toFloatOrNull()` da UI, que decide "Aprovado"
// ou "Reprovado". Esta versão reproduz o comportamento do Jsoup.
fn text(el: &ElementRef) -> String {
    el.text()
    .collect::<String>()
    .split_whitespace()
    .collect::<Vec<&str>>()
    .join(" ")
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

fn fallback_cell_text(row: &ElementRef, index: usize) -> Option<String> {
    let td_sel = sel("td");
    row.select(&td_sel).nth(index).map(|c| text(&c))
}

fn ensure_authenticated(doc: &Html) -> Result<(), CoreError> {
    if doc.select(&sel("#btn-login")).next().is_some() {
        return Err(CoreError::SessionExpired(
            "Página de login detectada — sessão inválida".to_string(),
        ));
    }
    Ok(())
}

fn is_media_row(row: &ElementRef) -> bool {
    let td_sel = sel("td");
    if let Some(first_cell) = row.select(&td_sel).next() {
        let cell_text = text(&first_cell).to_lowercase();
        if cell_text.contains("média") || cell_text.contains("media") {
            return true;
        }
        if first_cell.select(&sel("b i")).next().is_some() {
            return true;
        }
    }
    false
}

pub fn fetch_notas() -> Result<Vec<Nota>, CoreError> {
    let client = http_client();
    let mut cookies = Vec::new();

    let (resp, _) = execute_get_follow(&client, FEI_URL_NOTAS, &mut cookies)?;
    let html = resp.text()?;
    let doc = Html::parse_document(&html);

    ensure_authenticated(&doc)?;

    let mut notas = Vec::new();
    let panel_sel = sel("div.panel.panel-default");

    for panel in doc.select(&panel_sel) {
        let title_link = panel
        .select(&sel(".panel-title a.tabela-notas"))
        .next();

        let Some(title_link) = title_link else { continue };

        let title_text = text(&title_link);
        let partes: Vec<&str> = title_text.splitn(2, " - ").collect();
        if partes.len() != 2 {
            continue;
        }

        let codigo = partes[0].trim().to_string();
        let nome_disciplina = partes[1].trim().to_string();

        let Some(tabela) = panel.select(&sel("table.table.table-striped")).next() else {
            continue;
        };

        for row in tabela.select(&sel("tbody > tr")) {
            // Pula a linha da média
            if is_media_row(&row) {
                continue;
            }

            let tipo = cell_text_by_class_contains(&row, "avalia")
            .or_else(|| fallback_cell_text(&row, 0))
            .unwrap_or_default();

            let valor = cell_text_by_class_contains(&row, "valor")
            .or_else(|| fallback_cell_text(&row, 1))
            .unwrap_or_default();

            // ★ CORREÇÃO: Adiciona nota mesmo se valor for vazio, desde que tipo não seja vazio
            if !tipo.is_empty() {
                notas.push(Nota {
                    codigo_disciplina: codigo.clone(),
                           nome_disciplina: nome_disciplina.clone(),
                           tipo_prova: tipo,
                           valor,
                });
            }
        }
    }

    Ok(notas)
}

pub fn fetch_medias() -> Result<HashMap<String, String>, CoreError> {
    let client = http_client();
    let mut cookies = Vec::new();

    let (resp, _) = execute_get_follow(&client, FEI_URL_NOTAS, &mut cookies)?;
    let html = resp.text()?;
    let doc = Html::parse_document(&html);

    ensure_authenticated(&doc)?;

    let mut medias = HashMap::new();
    let panel_sel = sel("div.panel.panel-default");

    for panel in doc.select(&panel_sel) {
        let title_link = panel
        .select(&sel(".panel-title a.tabela-notas"))
        .next();

        let Some(title_link) = title_link else { continue };

        let title_text = text(&title_link);
        let partes: Vec<&str> = title_text.splitn(2, " - ").collect();
        if partes.is_empty() {
            continue;
        }

        let codigo = partes[0].trim().to_string();

        let Some(tabela) = panel.select(&sel("table.table")).next() else {
            continue;
        };

        // ★ Usa "tbody > tr" (igual à versão Kotlin antiga) em vez de "tr"
        // solto, para não varrer acidentalmente linhas de outro escopo.
        for row in tabela.select(&sel("tbody > tr")) {
            let td_sel = sel("td");
            let cells: Vec<ElementRef> = row.select(&td_sel).collect();
            if cells.is_empty() {
                continue;
            }

            let first = text(&cells[0]);
            if first.eq_ignore_ascii_case("Média") {
                if let Some(second) = cells.get(1) {
                    medias.insert(codigo.clone(), text(second));
                    break;
                }
            }
        }
    }

    Ok(medias)
}
