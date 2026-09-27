pub mod login;
pub mod disciplinas;
pub mod notas;
pub mod aulas;
pub mod perfil;
pub mod provas;
pub mod boletos;
pub mod carousel;

pub use login::login_fei;
pub use disciplinas::fetch_disciplinas;
pub use notas::{fetch_notas, fetch_medias};
pub use aulas::fetch_aulas;
pub use perfil::fetch_perfil;
pub use provas::fetch_provas_fei;
pub use boletos::{fetch_boletos, download_boleto};
pub use carousel::fetch_carousel;
