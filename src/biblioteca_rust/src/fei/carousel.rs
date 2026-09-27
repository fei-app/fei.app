use scraper::{Html, Selector};

use crate::error::CoreError;
use crate::http::{execute_get_follow, http_client};
use crate::models::CarouselItem;

const FEI_HOME_URL: &str = "https://interage.fei.org.br/secureserver/portal/graduacao/home";

fn sel(selector: &str) -> Selector {
    Selector::parse(selector).expect("Seletor CSS inválido")
}

fn ensure_authenticated(doc: &Html) -> Result<(), CoreError> {
    if doc.select(&sel("#btn-login")).next().is_some() {
        return Err(CoreError::SessionExpired(
            "Página de login detectada — sessão inválida".to_string(),
        ));
    }
    Ok(())
}

pub fn fetch_carousel() -> Result<Vec<CarouselItem>, CoreError> {
    let client = http_client();
    let mut cookies = Vec::new();

    let (resp, _) = execute_get_follow(&client, FEI_HOME_URL, &mut cookies)?;
    let html = resp.text()?;
    let doc = Html::parse_document(&html);

    ensure_authenticated(&doc)?;

    let mut items = Vec::new();

    for item in doc.select(&sel("#carousel-example-generic .item")) {
        let link_href = item
        .select(&sel("a"))
        .next()
        .and_then(|a| a.value().attr("href").map(|s| s.to_string()));

        let img_src = item
        .select(&sel("img"))
        .next()
        .and_then(|img| img.value().attr("src").map(|s| s.to_string()));

        if let (Some(link), Some(img)) = (link_href, img_src) {
            let absolute_image = if img.starts_with("http") {
                img
            } else {
                format!("https://interage.fei.org.br{}", img)
            };

            items.push(CarouselItem {
                image_url: Some(absolute_image),
                       link_url: Some(link),
            });
        }
    }

    Ok(items)
}
