use chrono::{Duration as ChronoDuration, TimeZone, Timelike};
use serde::{Deserialize, Serialize};
use std::collections::HashMap;

use crate::error::CoreError;
use crate::http::http_client;
use crate::models::ProvaCalendario;
use crate::moodle::login::get_moodle_token;

const MOODLE_API_URL: &str = "https://moodle.fei.edu.br/webservice/rest/server.php";

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
struct MoodleCourse {
    id: i64,
    shortname: String,
    fullname: String,
}

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
struct MoodleEvent {
    name: String,
    courseid: Option<i64>,
    timestart: i64,
}

fn moodle_api(
    token: &str,
    function: &str,
    extra: Vec<(String, String)>,
) -> Result<String, CoreError> {
    let client = http_client();

    let mut form = vec![
        ("wstoken".to_string(), token.to_string()),
        ("wsfunction".to_string(), function.to_string()),
        ("moodlewsrestformat".to_string(), "json".to_string()),
    ];

    form.extend(extra);

    let resp = client.post(MOODLE_API_URL).form(&form).send()?;
    let text = resp.text()?;

    if let Ok(value) = serde_json::from_str::<serde_json::Value>(&text) {
        if let Some(obj) = value.as_object() {
            if obj.contains_key("error") && !obj["error"].is_null() {
                let code = obj
                    .get("errorcode")
                    .and_then(|v| v.as_str())
                    .unwrap_or_default();

                let msg = obj
                    .get("error")
                    .and_then(|v| v.as_str())
                    .unwrap_or("erro desconhecido");

                if code == "invalidtoken" || code == "accessexception" {
                    return Err(CoreError::SessionExpired(format!(
                        "Token Moodle inválido ou acesso negado: {}",
                        msg
                    )));
                }
            }
        }
    }

    Ok(text)
}

fn format_moodle_timestamp(timestamp: i64) -> Result<(String, String), CoreError> {
    let utc = chrono::Utc
        .timestamp_opt(timestamp, 0)
        .single()
        .ok_or_else(|| CoreError::Parse("Timestamp inválido".into()))?;

    let mut zdt = utc.with_timezone(&chrono_tz::America::Sao_Paulo);

    if zdt.hour() == 0 && zdt.minute() == 0 && zdt.second() == 0 {
        zdt = zdt - ChronoDuration::days(1);
        zdt = zdt
            .with_hour(23)
            .and_then(|d| d.with_minute(59))
            .and_then(|d| d.with_second(0))
            .ok_or_else(|| CoreError::Parse("Erro ao ajustar data/hora".into()))?;
    }

    Ok((
        zdt.format("%d/%m").to_string(),
        zdt.format("%H:%M").to_string(),
    ))
}

pub fn fetch_eventos_moodle_with_token(token: &str) -> Result<Vec<ProvaCalendario>, CoreError> {
    if token.trim().is_empty() {
        return Err(CoreError::InvalidInput);
    }

    // site info
    let site_info_json = moodle_api(token, "core_webservice_get_site_info", vec![])?;
    let site_info: serde_json::Value = serde_json::from_str(&site_info_json)?;

    let user_id = site_info
        .get("userid")
        .and_then(|v| v.as_i64())
        .ok_or_else(|| CoreError::SessionExpired("userId do Moodle indisponível".into()))?;

    // courses
    let courses_json = moodle_api(
        token,
        "core_enrol_get_users_courses",
        vec![("userid".to_string(), user_id.to_string())],
    )?;

    let courses: Vec<MoodleCourse> = serde_json::from_str(&courses_json).unwrap_or_default();
    if courses.is_empty() {
        return Ok(Vec::new());
    }

    // events
    let mut params = Vec::new();

    for (index, course) in courses.iter().enumerate() {
        params.push((format!("events[courseids][{}]", index), course.id.to_string()));
    }

    params.push(("options[userevents]".to_string(), "1".to_string()));
    params.push(("options[siteevents]".to_string(), "1".to_string()));
    params.push(("options[timestart]".to_string(), "0".to_string()));

    let events_json = moodle_api(token, "core_calendar_get_calendar_events", params)?;
    let events_value: serde_json::Value = serde_json::from_str(&events_json)?;

    let events_array = events_value
        .get("events")
        .and_then(|v| v.as_array())
        .ok_or_else(|| CoreError::SessionExpired("Array de eventos Moodle ausente".into()))?;

    let events: Vec<MoodleEvent> =
        serde_json::from_value(serde_json::Value::Array(events_array.clone())).unwrap_or_default();

    let mapa: HashMap<i64, MoodleCourse> = courses
        .into_iter()
        .map(|c| (c.id, c))
        .collect();

    let mapped = events
        .into_iter()
        .map(|event| {
            let course = event.courseid.and_then(|id| mapa.get(&id));

            let codigo = course
                .map(|c| c.shortname.clone())
                .unwrap_or_else(|| "Moodle".to_string());

            let sala = course.map(|c| c.fullname.clone());

            let nome_limpo = event
                .name
                .replace("está marcado(a) para esta data", "")
                .trim()
                .to_string();

            let (data, hora) = format_moodle_timestamp(event.timestart)
                .unwrap_or_else(|_| ("--/--".to_string(), "--:--".to_string()));

            ProvaCalendario {
                disciplina: codigo,
                nome_disciplina: nome_limpo,
                data_prova: data,
                hora,
                sala,
                coordenador: String::new(),
                tipo_prova: "Moodle".to_string(),
            }
        })
        .collect();

    Ok(mapped)
}

pub fn fetch_eventos_moodle(user: &str, pass: &str) -> Result<Vec<ProvaCalendario>, CoreError> {
    let token_data = get_moodle_token(user, pass)?;

    if !token_data.success {
        return Err(CoreError::SessionExpired(token_data.error_message));
    }

    let token = token_data
        .token
        .ok_or_else(|| CoreError::SessionExpired("Token do Moodle indisponível".into()))?;

    fetch_eventos_moodle_with_token(&token)
}
