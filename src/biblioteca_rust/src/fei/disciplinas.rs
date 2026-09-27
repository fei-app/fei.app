use scraper::{ElementRef, Html, Selector};

use crate::error::CoreError;
use crate::http::{execute_get_follow, http_client};
use crate::models::Disciplina;

const FEI_URL_DISCIPLINAS: &str = "https://interage.fei.org.br/secureserver/portal/graduacao/sala-dos-professores/consultas/tabela-de-aulas";

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

pub fn fetch_disciplinas() -> Result<Vec<Disciplina>, CoreError> {
    let client = http_client();
    let mut cookies = Vec::new();

    let (resp, _) = execute_get_follow(&client, FEI_URL_DISCIPLINAS, &mut cookies)?;
    let html = resp.text()?;
    let doc = Html::parse_document(&html);

    ensure_authenticated(&doc)?;

    let tabela = doc
        .select(&sel("table.table.table-striped"))
        .next()
        .ok_or_else(|| CoreError::SessionExpired("Tabela de disciplinas não encontrada".into()))?;

    let mut disciplinas = Vec::new();
    let tr_sel = sel("tbody > tr");

    for row in tabela.select(&tr_sel) {
        let codigo = cell_text_by_class_contains(&row, "código")
            .or_else(|| fallback_cell_text(&row, 0))
            .unwrap_or_default();

        let nome = cell_text_by_class_contains(&row, "disciplina")
            .or_else(|| fallback_cell_text(&row, 1))
            .unwrap_or_default();

        if !codigo.is_empty() && !nome.is_empty() {
            disciplinas.push(Disciplina { codigo, nome });
        }
    }

    Ok(disciplinas)
}
