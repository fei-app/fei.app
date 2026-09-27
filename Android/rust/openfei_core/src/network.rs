use reqwest::blocking::Client;
use reqwest::redirect::Policy;
use std::time::Duration;

const NCSI_ENDPOINTS: &[(&str, &str)] = &[
    (
        "http://www.msftconnecttest.com/connecttest.txt",
        "Microsoft Connect Test",
    ),
    (
        "http://www.msftncsi.com/ncsi.txt",
        "Microsoft NCSI",
    ),
];

pub fn is_online() -> bool {
    let client = match Client::builder()
        .timeout(Duration::from_secs(8))
        .redirect(Policy::none())
        .build()
    {
        Ok(c) => c,
        Err(_) => return false,
    };

    for (url, expected) in NCSI_ENDPOINTS {
        let result = client.get(*url).send().and_then(|r| r.text());

        if let Ok(body) = result {
            if body.trim() == *expected {
                return true;
            }
        }
    }

    false
}
