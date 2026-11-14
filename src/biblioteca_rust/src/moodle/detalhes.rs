//! Detalhes (descrição + anexos) das tarefas/atividades do Moodle.
//!
//! Funções da API oficial usadas (Moodle Web Services):
//!  * `mod_assign_get_assignments` — tarefas (`assign`): `intro` (HTML) e
//!    `introattachments` (arquivos anexados pelo professor).
//!  * `core_course_get_contents`   — fallback genérico para qualquer outro módulo
//!    (quiz, resource, folder, forum...): `description` (HTML) e `contents` (arquivos).
//!  * `description` do próprio evento do calendário — último fallback.
//!
//! Regra de ouro: NENHUMA falha aqui pode derrubar a lista de eventos. Toda
//! chamada de enriquecimento é "best effort".

use scraper::{ElementRef, Html, Node};
use serde::Deserialize;
use std::collections::HashMap;

use crate::error::CoreError;
use crate::models::AnexoMoodle;
use crate::moodle::eventos::moodle_api;

// ---------------------------------------------------------------------
// Estruturas de resposta da API (apenas campos que usamos)
// ---------------------------------------------------------------------

#[derive(Debug, Clone, Deserialize, Default)]
pub struct MoodleFile {
    #[serde(default)]
    pub filename: String,
    #[serde(default)]
    pub filesize: i64,
    #[serde(default)]
    pub fileurl: String,
    #[serde(default)]
    pub mimetype: String,
    #[serde(default)]
    pub timemodified: i64,
    /// Só em core_course_get_contents: "file", "url", "content"...
    #[serde(default, rename = "type")]
    pub kind: String,
}

#[derive(Debug, Clone, Deserialize, Default)]
struct AssignListResponse {
    #[serde(default)]
    courses: Vec<AssignCourse>,
}

#[derive(Debug, Clone, Deserialize, Default)]
struct AssignCourse {
    #[serde(default)]
    assignments: Vec<Assignment>,
}

#[derive(Debug, Clone, Deserialize, Default)]
struct Assignment {
    id: i64,
    #[serde(default)]
    intro: String,
    #[serde(default)]
    introattachments: Vec<MoodleFile>,
}

#[derive(Debug, Clone, Deserialize, Default)]
struct CourseSection {
    #[serde(default)]
    modules: Vec<CourseModule>,
}

#[derive(Debug, Clone, Deserialize, Default)]
struct CourseModule {
    #[serde(default)]
    instance: i64,
    #[serde(default)]
    modname: String,
    #[serde(default)]
    description: String,
    #[serde(default)]
    contents: Vec<MoodleFile>,
}

/// Descrição + anexos de uma atividade.
#[derive(Debug, Clone, Default)]
pub struct DetalheAtividade {
    pub descricao: String,
    pub anexos: Vec<AnexoMoodle>,
}

/// Parâmetros mínimos do evento necessários para casar com a atividade.
#[derive(Debug, Clone)]
pub struct ChaveEvento {
    pub course_id: Option<i64>,
    pub modulename: String,
    pub instance: i64,
    pub description_html: String,
}

// ---------------------------------------------------------------------
// HTML -> texto puro
// ---------------------------------------------------------------------

const BLOCK_TAGS: &[&str] = &[
    "p", "div", "li", "ul", "ol", "tr", "table", "h1", "h2", "h3", "h4", "h5", "h6",
    "blockquote", "pre", "section", "article", "hr",
];

fn walk(el: ElementRef, out: &mut String) {
    for child in el.children() {
        match child.value() {
            Node::Text(t) => out.push_str(t),
            Node::Element(e) => {
                let name = e.name();
                if name == "script" || name == "style" {
                    continue;
                }
                if name == "br" {
                    out.push('\n');
                    continue;
                }
                let is_block = BLOCK_TAGS.contains(&name);
                if is_block {
                    out.push('\n');
                }
                if name == "li" {
                    out.push_str("• ");
                }
                if let Some(child_el) = ElementRef::wrap(child) {
                    walk(child_el, out);
                }
                if name == "td" || name == "th" {
                    out.push(' ');
                }
                // <li> e <tr> só quebram linha ANTES, para itens consecutivos
                // não ficarem separados por linha em branco.
                if is_block && name != "li" && name != "tr" {
                    out.push('\n');
                }
            }
            _ => {}
        }
    }
}

/// Converte o HTML do Moodle em texto puro legível (parágrafos separados por
/// linha em branco, listas com "•", entidades decodificadas).
pub fn html_para_texto(html: &str) -> String {
    if html.trim().is_empty() {
        return String::new();
    }

    let fragment = Html::parse_fragment(html);
    let mut raw = String::new();
    walk(fragment.root_element(), &mut raw);

    // Normaliza: NBSP -> espaço, tira espaços nas pontas das linhas e
    // colapsa linhas em branco consecutivas (máx. 1).
    let raw = raw.replace('\u{00A0}', " ").replace('\r', "");
    let mut out = String::with_capacity(raw.len());
    let mut blank_run = 0;

    for line in raw.split('\n') {
        let line = line.split_whitespace().collect::<Vec<_>>().join(" ");
        if line.is_empty() {
            blank_run += 1;
            if blank_run == 1 && !out.is_empty() {
                out.push('\n');
            }
        } else {
            if !out.is_empty() && !out.ends_with('\n') {
                out.push('\n');
            }
            blank_run = 0;
            out.push_str(&line);
            out.push('\n');
        }
    }

    out.trim().to_string()
}

// ---------------------------------------------------------------------
// Conversão de arquivos
// ---------------------------------------------------------------------

/// Remove qualquer `token=` da URL (o token não deve ir para o cache do app).
fn url_sem_token(url: &str) -> String {
    match url::Url::parse(url) {
        Ok(mut u) => {
            let kept: Vec<(String, String)> = u
                .query_pairs()
                .filter(|(k, _)| k != "token")
                .map(|(k, v)| (k.into_owned(), v.into_owned()))
                .collect();
            if kept.is_empty() {
                u.set_query(None);
            } else {
                u.query_pairs_mut().clear().extend_pairs(kept);
            }
            u.to_string()
        }
        Err(_) => url.to_string(),
    }
}

fn arquivos_para_anexos(files: &[MoodleFile], somente_tipo_file: bool) -> Vec<AnexoMoodle> {
    files
        .iter()
        .filter(|f| !f.fileurl.is_empty() && !f.filename.is_empty())
        .filter(|f| !somente_tipo_file || f.kind.is_empty() || f.kind == "file")
        .map(|f| AnexoMoodle {
            nome: f.filename.clone(),
            url: url_sem_token(&f.fileurl),
            tamanho: f.filesize,
            mime_type: f.mimetype.clone(),
            modificado: f.timemodified,
        })
        .collect()
}

// ---------------------------------------------------------------------
// Resolvedor (cache por chamada)
// ---------------------------------------------------------------------

/// Chamada "best effort": qualquer erro (rede, token, função não habilitada no
/// serviço) vira `Err` e o chamador simplesmente ignora o enriquecimento.
fn chamar(token: &str, function: &str, params: Vec<(String, String)>) -> Result<String, CoreError> {
    moodle_api(token, function, params)
}

/// Busca os detalhes de todas as atividades dos cursos informados com o mínimo
/// de requisições: 1 chamada `mod_assign_get_assignments` (todos os cursos de
/// uma vez) + 1 `core_course_get_contents` por curso, só se houver evento de
/// outro tipo de módulo naquele curso.
pub struct ResolvedorDetalhes<'a> {
    token: &'a str,
    assigns: HashMap<i64, Assignment>,
    contents: HashMap<i64, Vec<CourseModule>>,
    contents_tentados: Vec<i64>,
}

impl<'a> ResolvedorDetalhes<'a> {
    pub fn new(token: &'a str, course_ids: &[i64]) -> Self {
        let mut r = Self {
            token,
            assigns: HashMap::new(),
            contents: HashMap::new(),
            contents_tentados: Vec::new(),
        };
        r.carregar_assigns(course_ids);
        r
    }

    fn carregar_assigns(&mut self, course_ids: &[i64]) {
        if course_ids.is_empty() {
            return;
        }
        let params: Vec<(String, String)> = course_ids
            .iter()
            .enumerate()
            .map(|(i, id)| (format!("courseids[{}]", i), id.to_string()))
            .collect();

        let Ok(json) = chamar(self.token, "mod_assign_get_assignments", params) else {
            return;
        };
        // Em caso de erro a API devolve {"exception":...}; o parse com defaults
        // simplesmente produz uma lista vazia.
        let Ok(resp) = serde_json::from_str::<AssignListResponse>(&json) else {
            return;
        };
        for course in resp.courses {
            for a in course.assignments {
                self.assigns.insert(a.id, a);
            }
        }
    }

    fn carregar_contents(&mut self, course_id: i64) {
        if self.contents_tentados.contains(&course_id) {
            return;
        }
        self.contents_tentados.push(course_id);

        let params = vec![("courseid".to_string(), course_id.to_string())];
        let Ok(json) = chamar(self.token, "core_course_get_contents", params) else {
            return;
        };
        let Ok(sections) = serde_json::from_str::<Vec<CourseSection>>(&json) else {
            return;
        };
        let modules: Vec<CourseModule> = sections.into_iter().flat_map(|s| s.modules).collect();
        self.contents.insert(course_id, modules);
    }

    pub fn resolver(&mut self, chave: &ChaveEvento) -> DetalheAtividade {
        // 1) Tarefas (assign)
        if chave.modulename == "assign" {
            if let Some(a) = self.assigns.get(&chave.instance) {
                let mut descricao = html_para_texto(&a.intro);
                if descricao.is_empty() {
                    descricao = html_para_texto(&chave.description_html);
                }
                return DetalheAtividade {
                    descricao,
                    anexos: arquivos_para_anexos(&a.introattachments, false),
                };
            }
        }

        // 2) Outros módulos (quiz, resource, folder, forum, ...)
        if !chave.modulename.is_empty() && chave.instance > 0 {
            if let Some(course_id) = chave.course_id {
                self.carregar_contents(course_id);
                if let Some(modules) = self.contents.get(&course_id) {
                    if let Some(m) = modules
                        .iter()
                        .find(|m| m.modname == chave.modulename && m.instance == chave.instance)
                    {
                        let mut descricao = html_para_texto(&m.description);
                        if descricao.is_empty() {
                            descricao = html_para_texto(&chave.description_html);
                        }
                        return DetalheAtividade {
                            descricao,
                            anexos: arquivos_para_anexos(&m.contents, true),
                        };
                    }
                }
            }
        }

        // 3) Somente a descrição que veio no próprio evento
        DetalheAtividade {
            descricao: html_para_texto(&chave.description_html),
            anexos: Vec::new(),
        }
    }
}

#[cfg(test)]
mod testes {
    use super::*;

    #[test]
    fn html_vira_texto() {
        let html = "<div class=\"no-overflow\"><p>Entregar o <b>relat&oacute;rio</b>&nbsp;at&eacute; sexta.</p>\
                    <p>Itens:<br>a) um</p><ul><li>dois</li><li>tr&ecirc;s</li></ul><p><br></p><p>Fim</p></div>";
        let t = html_para_texto(html);
        assert_eq!(t, "Entregar o relatório até sexta.\n\nItens:\na) um\n\n• dois\n• três\n\nFim");
    }

    #[test]
    fn html_vazio() {
        assert_eq!(html_para_texto(""), "");
        assert_eq!(html_para_texto("<p><br></p>"), "");
    }

    #[test]
    fn token_removido_da_url() {
        let u = url_sem_token("https://moodle.fei.edu.br/webservice/pluginfile.php/1/mod_assign/introattachment/0/a%20b.pdf?token=abc&forcedownload=1");
        assert_eq!(u, "https://moodle.fei.edu.br/webservice/pluginfile.php/1/mod_assign/introattachment/0/a%20b.pdf?forcedownload=1");
        let u = url_sem_token("https://moodle.fei.edu.br/webservice/pluginfile.php/1/x.pdf?token=abc");
        assert_eq!(u, "https://moodle.fei.edu.br/webservice/pluginfile.php/1/x.pdf");
    }

    #[test]
    fn parse_assign() {
        let json = r#"{"courses":[{"id":5,"assignments":[{"id":42,"cmid":9,"intro":"<p>Oi</p>",
          "introattachments":[{"filename":"enunciado.pdf","filesize":1234,"fileurl":"https://m/webservice/pluginfile.php/1/a.pdf","mimetype":"application/pdf","timemodified":1700000000}]}]}],"warnings":[]}"#;
        let r: AssignListResponse = serde_json::from_str(json).unwrap();
        assert_eq!(r.courses[0].assignments[0].id, 42);
        let anexos = arquivos_para_anexos(&r.courses[0].assignments[0].introattachments, false);
        assert_eq!(anexos[0].nome, "enunciado.pdf");
        assert_eq!(anexos[0].tamanho, 1234);
    }
}
