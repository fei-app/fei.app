pub mod login;
pub mod eventos;
pub mod detalhes;
pub mod anexos;

pub use login::{get_moodle_token, moodle_login};
pub use eventos::{fetch_eventos_moodle, fetch_eventos_moodle_with_token};
pub use anexos::download_anexo_moodle;
