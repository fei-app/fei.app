use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
#[serde(rename_all = "camelCase")]
pub struct Disciplina {
    pub codigo: String,
    pub nome: String,
}

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
#[serde(rename_all = "camelCase")]
pub struct Nota {
    pub codigo_disciplina: String,
    pub nome_disciplina: String,
    pub tipo_prova: String,
    pub valor: String,
}

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
#[serde(rename_all = "camelCase")]
pub struct Perfil {
    pub nome: String,
    pub matricula: String,
    pub curso: String,
    pub email: String,
}

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
#[serde(rename_all = "camelCase")]
pub struct Aula {
    pub dia_semana: String,
    pub codigo_disciplina: String,
    pub nome_disciplina: String,
    pub sala: String,
    pub hora_inicio: String,
    pub hora_fim: String,
}

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
#[serde(rename_all = "camelCase")]
pub struct ProvaCalendario {
    pub disciplina: String,
    pub nome_disciplina: String,
    pub data_prova: String,
    pub hora: String,
    pub sala: Option<String>,
    pub coordenador: String,
    pub tipo_prova: String,
}

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
#[serde(rename_all = "camelCase")]
pub struct Boleto {
    pub vencimento: String,
    pub status: String,
    pub data_pagamento: String,
    pub titulo_id: String,
}

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
#[serde(rename_all = "camelCase")]
pub struct CarouselItem {
    pub image_url: Option<String>,
    pub link_url: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
#[serde(rename_all = "camelCase")]
pub struct CookieRecord {
    pub origin: String,
    pub cookie_line: String,
}

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
#[serde(rename_all = "camelCase")]
pub struct LoginData {
    pub success: bool,
    pub error_message: String,
    pub is_network_error: bool,
    pub cookies: Vec<CookieRecord>,
}

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
#[serde(rename_all = "camelCase")]
pub struct MoodleTokenData {
    pub success: bool,
    pub error_message: String,
    pub is_network_error: bool,
    pub token: Option<String>,
    pub cookies: Vec<CookieRecord>,
}

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
#[serde(rename_all = "camelCase")]
pub struct DownloadBoletoResult {
    pub path: String,
    pub size: usize,
}
