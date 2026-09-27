use regex::Regex;
use scraper::{ElementRef, Html, Selector};
use std::collections::HashMap;

use crate::error::CoreError;
use crate::fei::disciplinas::fetch_disciplinas;
use crate::http::{execute_get_follow, http_client};
use crate::models::Aula;

const FEI_URL_AULAS: &str = "https://interage.fei.org.br/secureserver/portal/graduacao/secretaria/consultas/horario/arquivo";

fn sel(selector: &str) -> Selector {
    Selector::parse(selector).expect("Seletor CSS inválido")
}

fn text(el: &ElementRef) -> String {
    el.text().collect::<String>().trim().to_string()
}

fn ensure_authenticated(doc: &Html) -> Result<(), CoreError> {
    if doc.select(&sel("#btn-login")).next().is_some() {
        return Err(CoreError::SessionExpired(
            "Página de login detectada — sessão inválida".to_string(),
        ));
    }
    Ok(())
}

fn extrair_horario(texto: &str) -> Option<(String, String)> {
    let re = Regex::new(r"(\d{2}:\d{2})\s*-\s*(\d{2}:\d{2})").ok()?;
    let caps = re.captures(texto)?;
    Some((caps[1].to_string(), caps[2].to_string()))
}

pub fn fetch_aulas() -> Result<Vec<Aula>, CoreError> {
    let client = http_client();
    let mut cookies = Vec::new();

    let (resp, _) = execute_get_follow(&client, FEI_URL_AULAS, &mut cookies)?;
    let html = resp.text()?;
    let doc = Html::parse_document(&html);

    ensure_authenticated(&doc)?;

    let tabela = doc
        .select(&sel("#tb_princ"))
        .next()
        .ok_or_else(|| {
            CoreError::SessionExpired("Tabela de horários não encontrada - sessão inválida".into())
        })?;

    let tr_sel = sel("tr");
    let rows: Vec<ElementRef> = tabela.select(&tr_sel).collect();

    if rows.len() < 3 {
        return Ok(Vec::new());
    }

    let td_sel = sel("td");
    let mut aulas_por_dia: HashMap<String, Vec<Aula>> = HashMap::new();

    let colunas_por_dia = vec![
        ("Segunda", 1usize, 3usize),
        ("Terça", 4, 6),
        ("Quarta", 7, 9),
        ("Quinta", 10, 12),
        ("Sexta", 13, 15),
        ("Sábado", 17, 19),
    ];

    for (dia, _, _) in &colunas_por_dia {
        aulas_por_dia.insert(dia.to_string(), Vec::new());
    }

    let whitespace = Regex::new(r"\s+").unwrap();

    for row in rows.iter().skip(2) {
        let cells: Vec<ElementRef> = row.select(&td_sel).collect();
        if cells.len() < 20 {
            continue;
        }

        let hora_texto = text(&cells[0]);
        let horario_padrao = extrair_horario(&hora_texto);

        let mut horario_sabado = None;
        for cell in &cells {
            let is_sabado = cell
                .value()
                .attr("class")
                .map(|c| c.to_lowercase().contains("sabado"))
                .unwrap_or(false);

            if is_sabado {
                let txt = text(cell);
                if let Some(h) = extrair_horario(&txt) {
                    horario_sabado = Some(h);
                    break;
                }
            }
        }

        for (dia, col_disc, col_sala) in &colunas_por_dia {
            if *col_disc >= cells.len() || *col_sala >= cells.len() {
                continue;
            }

            let codigo = text(&cells[*col_disc]);
            let sala = text(&cells[*col_sala]);

            if codigo.trim().is_empty() || codigo.trim() == " " {
                continue;
            }

            let codigo = whitespace
                .replace_all(codigo.trim(), " ")
                .trim()
                .to_string();

            let horario = if *dia == "Sábado" {
                horario_sabado.clone()
            } else {
                horario_padrao.clone()
            };

            if let Some((inicio, fim)) = horario {
                aulas_por_dia.get_mut(*dia).unwrap().push(Aula {
                    dia_semana: dia.to_string(),
                    codigo_disciplina: codigo,
                    nome_disciplina: String::new(),
                    sala,
                    hora_inicio: inicio,
                    hora_fim: fim,
                });
            }
        }
    }

    let mut grouped: Vec<Aula> = Vec::new();

    for (_, mut lista) in aulas_por_dia {
        if lista.is_empty() {
            continue;
        }

        lista.sort_by(|a, b| a.hora_inicio.cmp(&b.hora_inicio));

        let mut current = lista[0].clone();

        for aula in lista.into_iter().skip(1) {
            if current.codigo_disciplina == aula.codigo_disciplina {
                current.hora_fim = aula.hora_fim;
                current.sala = aula.sala;
            } else {
                grouped.push(current);
                current = aula;
            }
        }

        grouped.push(current);
    }

    // Preenche nomes das disciplinas
    let disciplinas = fetch_disciplinas().unwrap_or_default();
    let mapa: HashMap<String, String> = disciplinas
        .into_iter()
        .map(|d| (d.codigo, d.nome))
        .collect();

    for aula in grouped.iter_mut() {
        aula.nome_disciplina = mapa
            .get(&aula.codigo_disciplina)
            .cloned()
            .unwrap_or_else(|| aula.codigo_disciplina.clone());
    }

    Ok(grouped)
}
